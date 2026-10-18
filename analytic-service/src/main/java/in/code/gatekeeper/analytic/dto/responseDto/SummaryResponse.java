package in.code.gatekeeper.analytic.dto.responseDto;

import java.util.List;

/**
 * Headline numbers for the range. deltas: change vs the previous period of the same length as a fraction
 * (0.12 = +12%), null when the previous period had nothing to compare with. trends: one value per chart bucket.
 */
public record SummaryResponse(String range, long requests, double errorRate, double serverErrorRate, long p50,
                              long p95, long rateLimited, long replays, Deltas deltas, Trends trends) {

    public record Deltas(Double requests, Double errorRate, Double p95, Double rateLimited, Double replays) {
    }

    public record Trends(List<Long> requests, List<Double> errorRate, List<Long> p95, List<Long> rateLimited,
                         List<Long> replays) {
    }
}
