package in.code.gatekeeper.analytic.service;

import in.code.gatekeeper.analytic.util.LatencyHistogram;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Building blocks of the MongoDB aggregation pipelines, written as plain pipeline documents so they read like the
 * MongoDB docs (decision #73):
 *   - rollup: traffic_events of a time range -> one traffic_minutes document per organization + route + minute,
 *     merged over what was there (recomputed, never incremented, so reruns and late events can't double-count)
 *   - views: the dashboards' numbers summed from traffic_minutes; percentiles from the summed latency histogram
 */
public final class TrafficAggregations {

    private static final long MINUTE_MS = 60_000;
    private static final List<String> COUNTS = List.of("requests", "s2xx", "s4xx", "s5xx", "rateLimited", "replays",
            "conflicts", "mismatches", "executed");

    private TrafficAggregations() {
    }

    // ---------- rollup (over traffic_events) ----------

    public static List<Document> rollup(Instant from, Instant to, String minutesCollection) {
        Document minute = new Document("$toDate", bucketStart("$timestamp", MINUTE_MS));
        Document group = new Document("_id", new Document("organizationId", "$organizationId")
                .append("routeId", "$routeId").append("minute", minute))
                .append("organizationSlug", new Document("$first", "$organizationSlug"))
                .append("requests", new Document("$sum", 1))
                // "s2xx" counts everything below 400 (redirects are not errors), so s2xx + s4xx + s5xx = requests
                .append("s2xx", countIf(new Document("$lt", List.of("$status", 400))))
                .append("s4xx", countIf(between("$status", 400, 500)))
                .append("s5xx", countIf(new Document("$gte", List.of("$status", 500))))
                .append("rateLimited", countIf(new Document("$eq", List.of("$status", 429))))
                .append("replays", outcome("REPLAYED"))
                .append("conflicts", outcome("IN_PROGRESS_CONFLICT"))
                .append("mismatches", outcome("BODY_MISMATCH"))
                .append("executed", outcome("EXECUTED"));
        List<Long> bounds = LatencyHistogram.BOUNDS;
        for (int bucket = 0; bucket < LatencyHistogram.BUCKETS; bucket++) {
            Document inBucket = bucket == 0 ? new Document("$lt", List.of("$latencyMs", bounds.getFirst()))
                    : bucket == bounds.size() ? new Document("$gte", List.of("$latencyMs", bounds.getLast()))
                    : new Document("$and", List.of(new Document("$gte", List.of("$latencyMs", bounds.get(bucket - 1))),
                            new Document("$lt", List.of("$latencyMs", bounds.get(bucket)))));
            group.append(LatencyHistogram.field(bucket), countIf(inBucket));
        }
        return List.of(
                new Document("$match", timeRange("timestamp", from, to)),
                new Document("$group", group),
                new Document("$addFields", new Document("organizationId", "$_id.organizationId")
                        .append("routeId", "$_id.routeId").append("minute", "$_id.minute")),
                new Document("$merge", new Document("into", minutesCollection).append("on", "_id")
                        .append("whenMatched", "replace").append("whenNotMatched", "insert")));
    }

    // The minutes (epoch ms) that got events stored in (after, until]: the ones the rollup has to recompute
    public static List<Document> touchedMinutes(Instant after, Instant until) {
        return List.of(
                new Document("$match", new Document("storedAt",
                        new Document("$gt", Date.from(after)).append("$lte", Date.from(until)))),
                new Document("$group", new Document("_id", bucketStart("$timestamp", MINUTE_MS))));
    }

    // ---------- views (over traffic_minutes) ----------

    // minute in [from, to), optionally one organization (null = every organization)
    public static Document match(Instant from, Instant to, Long organizationId) {
        Document filter = timeRange("minute", from, to);
        if (organizationId != null) {
            filter.append("organizationId", organizationId);
        }
        return new Document("$match", filter);
    }

    // The epoch-aligned start (in ms) of the bucket a minute falls into
    public static Document bucketStart(long stepMs) {
        return bucketStart("$minute", stepMs);
    }

    // Every count and histogram bucket summed per group
    public static Document statsGroup(Object groupId) {
        Document group = new Document("_id", groupId);
        for (String count : COUNTS) {
            group.append(count, sum(count));
        }
        for (int bucket = 0; bucket < LatencyHistogram.BUCKETS; bucket++) {
            group.append(LatencyHistogram.field(bucket), sum(LatencyHistogram.field(bucket)));
        }
        return new Document("$group", group);
    }

    public static Document sum(String field) {
        return new Document("$sum", "$" + field);
    }

    /** The numbers of one group, read back from a statsGroup result (or EMPTY when nothing matched). */
    public record Stats(long requests, long s2xx, long s4xx, long s5xx, long rateLimited, long replays,
                        long p50, long p95) {

        public static final Stats EMPTY = new Stats(0, 0, 0, 0, 0, 0, 0, 0);

        public static Stats of(Document group) {
            long[] histogram = new long[LatencyHistogram.BUCKETS];
            for (int bucket = 0; bucket < histogram.length; bucket++) {
                histogram[bucket] = number(group, LatencyHistogram.field(bucket));
            }
            return new Stats(number(group, "requests"), number(group, "s2xx"), number(group, "s4xx"),
                    number(group, "s5xx"), number(group, "rateLimited"), number(group, "replays"),
                    LatencyHistogram.percentile(histogram, 0.5), LatencyHistogram.percentile(histogram, 0.95));
        }

        public double errorRate() {
            return requests == 0 ? 0 : (double) (s4xx + s5xx) / requests;
        }

        public double serverErrorRate() {
            return requests == 0 ? 0 : (double) s5xx / requests;
        }

        public static long number(Document group, String field) {
            Number value = group.get(field, Number.class);
            return value == null ? 0 : value.longValue();
        }
    }

    // ---------- shared ----------

    private static Document timeRange(String field, Instant from, Instant to) {
        return new Document(field, new Document("$gte", Date.from(from)).append("$lt", Date.from(to)));
    }

    private static Document bucketStart(String dateField, long stepMs) {
        Document epochMs = new Document("$toLong", dateField);
        return new Document("$subtract", List.of(epochMs, new Document("$mod", List.of(epochMs, stepMs))));
    }

    private static Document outcome(String outcome) {
        return countIf(new Document("$eq", List.of("$idempotencyOutcome", outcome)));
    }

    private static Document countIf(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1, 0)));
    }

    private static Document between(String field, int fromInclusive, int toExclusive) {
        return new Document("$and", List.of(new Document("$gte", List.of(field, fromInclusive)),
                new Document("$lt", List.of(field, toExclusive))));
    }
}
