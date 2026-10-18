package in.code.gatekeeper.analytic.dto.responseDto;

import java.util.List;

// One route's traffic in the range; trend: requests in 12 equal slices of the range
public record RouteStatsResponse(String organizationSlug, String routeId, long requests, double errorRate, long p50,
                                 long p95, long rateLimited, long replays, List<Long> trend) {
}
