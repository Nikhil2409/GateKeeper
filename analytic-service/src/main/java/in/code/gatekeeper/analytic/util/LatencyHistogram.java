package in.code.gatekeeper.analytic.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed latency buckets for the per-minute rollups (decision #73): field h0 counts requests under BOUNDS[0] ms,
 * h1 those from BOUNDS[0] to BOUNDS[1] ... and the last one everything from the largest bound up. Percentiles are
 * read back from summed buckets, interpolated inside the bucket: approximate, like Prometheus histograms.
 */
public final class LatencyHistogram {

    // 1, 2, 3 ... then each bound about 20% above the last, up to a minute: a percentile is off by at most ~10%
    public static final List<Long> BOUNDS = bounds(1.2, 60_000);
    public static final int BUCKETS = BOUNDS.size() + 1;

    private LatencyHistogram() {
    }

    private static List<Long> bounds(double factor, long max) {
        List<Long> bounds = new ArrayList<>();
        for (double bound = 1; bound <= max; bound *= factor) {
            long rounded = Math.round(bound);
            if (bounds.isEmpty() || rounded > bounds.getLast()) {
                bounds.add(rounded);
            }
        }
        return List.copyOf(bounds);
    }

    public static String field(int bucket) {
        return "h" + bucket;
    }

    // The latency (ms) below which a share p of the requests fall; 0 without requests
    public static long percentile(long[] counts, double p) {
        long total = 0;
        for (long count : counts) {
            total += count;
        }
        if (total == 0) {
            return 0;
        }
        double target = p * total;
        long seen = 0;
        for (int bucket = 0; bucket < counts.length; bucket++) {
            if (counts[bucket] > 0 && seen + counts[bucket] >= target) {
                if (bucket == BOUNDS.size()) {
                    return BOUNDS.getLast();
                }
                long low = bucket == 0 ? 0 : BOUNDS.get(bucket - 1);
                long high = BOUNDS.get(bucket);
                return Math.round(low + (high - low) * ((target - seen) / counts[bucket]));
            }
            seen += counts[bucket];
        }
        return BOUNDS.getLast();
    }
}
