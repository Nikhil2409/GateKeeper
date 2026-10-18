package in.code.gatekeeper.analytic.dto.responseDto;

import in.code.gatekeeper.analytic.service.TrafficAggregations.Stats;

import java.time.Instant;

// One chart bucket; t is the bucket start
public record TimeBucketResponse(Instant t, long requests, long s2xx, long s4xx, long s5xx, long rateLimited,
                                 long replays, long p50, long p95, double errorRate) {

    public static TimeBucketResponse from(Instant t, Stats stats) {
        return new TimeBucketResponse(t, stats.requests(), stats.s2xx(), stats.s4xx(), stats.s5xx(),
                stats.rateLimited(), stats.replays(), stats.p50(), stats.p95(), stats.errorRate());
    }
}
