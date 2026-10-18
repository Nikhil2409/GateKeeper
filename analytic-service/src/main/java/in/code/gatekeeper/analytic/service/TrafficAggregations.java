package in.code.gatekeeper.analytic.service;

import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Building blocks of the MongoDB aggregation pipelines over traffic_events, shared by the services.
 * Written as plain pipeline documents so they read like the MongoDB docs. Latency percentiles use $percentile
 * (MongoDB 7.0+, "approximate" method: fast, within about 1% at these volumes).
 */
public final class TrafficAggregations {

    private TrafficAggregations() {
    }

    // timestamp in [from, to), optionally one organization (null = every organization)
    public static Document match(Instant from, Instant to, Long organizationId) {
        Document filter = new Document("timestamp", new Document("$gte", Date.from(from)).append("$lt", Date.from(to)));
        if (organizationId != null) {
            filter.append("organizationId", organizationId);
        }
        return new Document("$match", filter);
    }

    // The epoch-aligned start (in ms) of the bucket an event falls into
    public static Document bucketStart(long stepMs) {
        Document epochMs = new Document("$toLong", "$timestamp");
        return new Document("$subtract", List.of(epochMs, new Document("$mod", List.of(epochMs, stepMs))));
    }

    /**
     * The per-group numbers every view uses. "s2xx" counts everything below 400 (redirects are not errors), so
     * s2xx + s4xx + s5xx = requests. rateLimited counts every 429 (route limit and per-user quota).
     */
    public static Document statsGroup(Object groupId) {
        return new Document("$group", new Document("_id", groupId)
                .append("requests", new Document("$sum", 1))
                .append("s2xx", countIf(new Document("$lt", List.of("$status", 400))))
                .append("s4xx", countIf(between("$status", 400, 500)))
                .append("s5xx", countIf(new Document("$gte", List.of("$status", 500))))
                .append("rateLimited", countIf(new Document("$eq", List.of("$status", 429))))
                .append("replays", countIf(new Document("$eq", List.of("$idempotencyOutcome", "REPLAYED"))))
                .append("latency", new Document("$percentile", new Document("input", "$latencyMs")
                        .append("p", List.of(0.5, 0.95))
                        .append("method", "approximate"))));
    }

    public static Document countIf(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }

    public static Document between(String field, int fromInclusive, int toExclusive) {
        return new Document("$and", List.of(new Document("$gte", List.of(field, fromInclusive)),
                new Document("$lt", List.of(field, toExclusive))));
    }

    /** The numbers of one group, read back from a statsGroup result (or EMPTY when nothing matched). */
    public record Stats(long requests, long s2xx, long s4xx, long s5xx, long rateLimited, long replays,
                        long p50, long p95) {

        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0);

        public static Stats of(Document group) {
            List<?> latency = group.getList("latency", Object.class);
            return new Stats(number(group, "requests"), number(group, "s2xx"), number(group, "s4xx"),
                    number(group, "s5xx"), number(group, "rateLimited"), number(group, "replays"),
                    percentile(latency, 0), percentile(latency, 1));
        }

        public double errorRate() {
            return requests == 0 ? 0 : (double) (s4xx + s5xx) / requests;
        }

        public double serverErrorRate() {
            return requests == 0 ? 0 : (double) s5xx / requests;
        }

        private static long number(Document group, String field) {
            Number value = group.get(field, Number.class);
            return value == null ? 0 : value.longValue();
        }

        private static long percentile(List<?> latency, int index) {
            if (latency == null || latency.size() <= index || !(latency.get(index) instanceof Number value)) {
                return 0;
            }
            return Math.round(value.doubleValue());
        }
    }
}
