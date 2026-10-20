package in.code.gatekeeper.analytic;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.kafka.TrafficEventConsumer;
import in.code.gatekeeper.analytic.kafka.TrafficEventMessage;
import in.code.gatekeeper.analytic.service.TrafficRollupService;
import in.code.gatekeeper.analytic.support.AnalyticsIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingestion stores each traffic event once, keyed by the gateway's request id, so a Kafka redelivery replaces
 * instead of duplicating (decision #51). The rollups (decision #73) must match the stored events exactly: per
 * organization, route and minute, across reruns of the job and for events that arrive late.
 */
class IngestionAndRollupTest extends AnalyticsIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private TrafficEventConsumer consumer;
    @Autowired
    private TrafficRollupService rollup;
    @Autowired
    private MongoTemplate mongo;

    // The job's very first run backfills everything, which would hide a bug in finding changed minutes: make sure
    // every test exercises the normal incremental run
    @BeforeEach
    void pastTheBackfill() {
        rollup.rollUp();
    }

    @Test
    void redeliveredEventIsStoredOnce() {
        long org = newOrganization();
        TrafficEventMessage event = event(org, "orders", 200, 12, Instant.now().minus(Duration.ofMinutes(1)));
        deliver(event);
        deliver(event);
        deliver(event);
        assertThat(storedFor(org)).isEqualTo(1);
    }

    @Test
    void unreadableMessageIsSkippedAndTheNextOneStillStored() {
        long org = newOrganization();
        consumer.onMessage(new ConsumerRecord<>("gateway.traffic-events", 0, 0, "x", "{not json"));
        deliver(event(org, "orders", 200, 5, Instant.now().minus(Duration.ofMinutes(1))));
        assertThat(storedFor(org)).isEqualTo(1);
    }

    @Test
    void rollupsMatchTheStoredEventsExactly() {
        long org = newOrganization();
        Random random = new Random(42);
        Instant base = Instant.now().minus(Duration.ofMinutes(20));
        int[] statuses = {200, 200, 200, 201, 404, 429, 500, 503};
        Map<String, long[]> expected = new HashMap<>();   // route|minute -> requests, 4xx, 5xx, 429
        for (int i = 0; i < 600; i++) {
            String route = List.of("orders", "items", "pay").get(random.nextInt(3));
            Instant at = base.plusSeconds(random.nextInt(15 * 60));
            int status = statuses[random.nextInt(statuses.length)];
            deliver(event(org, route, status, 1 + random.nextInt(400), at));
            long[] counts = expected.computeIfAbsent(route + "|" + (at.toEpochMilli() / 60_000 * 60_000), k -> new long[4]);
            counts[0]++;
            counts[1] += status >= 400 && status < 500 ? 1 : 0;
            counts[2] += status >= 500 ? 1 : 0;
            counts[3] += status == 429 ? 1 : 0;
        }
        rollup.rollUp();
        rollup.rollUp();   // a rerun recomputes, it never adds again

        assertThat(rollupsOf(org)).isEqualTo(asLists(expected));
    }

    @Test
    void lateEventIsAddedToItsOldMinute() {
        long org = newOrganization();
        Instant oldMinute = Instant.now().minus(Duration.ofMinutes(30));
        deliver(event(org, "orders", 200, 10, oldMinute));
        rollup.rollUp();
        assertThat(rollupsOf(org).values().iterator().next().getFirst()).isEqualTo(1L);

        // arrives much later, e.g. after a Kafka backlog: its minute must be recomputed, not missed
        deliver(event(org, "orders", 200, 10, oldMinute.plusSeconds(1)));
        rollup.rollUp();
        assertThat(rollupsOf(org).values().iterator().next().getFirst()).isEqualTo(2L);
    }

    @Test
    void dashboardPercentilesStayWithinTheHistogramsAccuracy() {
        long org = newOrganization();
        Instant at = Instant.now().minus(Duration.ofMinutes(2));
        // 100 requests at 1..100 ms: exact p50 = 50, p95 = 95
        for (int latency = 1; latency <= 100; latency++) {
            deliver(event(org, "orders", 200, latency, at));
        }
        rollup.rollUp();
        var summary = get("/analytics/summary?range=1h", token("TENANT_ADMIN", org)).body();
        assertThat(summary.get("requests").asLong()).isEqualTo(100);
        // buckets are ~20% wide, so an estimate may be off by up to about one bucket
        assertThat(summary.get("p50").asLong()).isBetween(40L, 60L);
        assertThat(summary.get("p95").asLong()).isBetween(80L, 114L);
    }

    private void deliver(TrafficEventMessage event) {
        consumer.onMessage(new ConsumerRecord<>("gateway.traffic-events", 0, 0,
                String.valueOf(event.organizationId()), JSON.writeValueAsString(event)));
    }

    private static TrafficEventMessage event(long org, String route, int status, long latencyMs, Instant at) {
        return new TrafficEventMessage(UUID.randomUUID().toString(), "corr", at, "gw-1", org, "org-" + org, route,
                "GET", "/org-" + org + "/" + route, status, latencyMs, "10.0.0.1",
                status == 429 ? "RATE_LIMITED" : null, null, null);
    }

    private long storedFor(long org) {
        return mongo.count(new Query(Criteria.where("organizationId").is(org)), TrafficEventDocument.class);
    }

    // route|minute -> requests, 4xx, 5xx, 429, read straight from traffic_minutes
    private Map<String, List<Long>> rollupsOf(long org) {
        Map<String, List<Long>> rollups = new HashMap<>();
        for (Document minute : mongo.getCollection("traffic_minutes").find(new Document("organizationId", org))) {
            rollups.put(minute.getString("routeId") + "|" + minute.getDate("minute").getTime(), List.of(
                    minute.get("requests", Number.class).longValue(), minute.get("s4xx", Number.class).longValue(),
                    minute.get("s5xx", Number.class).longValue(), minute.get("rateLimited", Number.class).longValue()));
        }
        return rollups;
    }

    private static Map<String, List<Long>> asLists(Map<String, long[]> counts) {
        Map<String, List<Long>> lists = new HashMap<>();
        counts.forEach((key, value) -> lists.put(key, List.of(value[0], value[1], value[2], value[3])));
        return lists;
    }

    private static long newOrganization() {
        return ThreadLocalRandom.current().nextLong(3_000_000_000L, 4_000_000_000L);
    }
}
