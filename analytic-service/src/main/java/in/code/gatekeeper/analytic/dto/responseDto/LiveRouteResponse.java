package in.code.gatekeeper.analytic.dto.responseDto;

// One route right now, over the last minute (from Prometheus, not MongoDB); latencies in ms
public record LiveRouteResponse(String organizationSlug, String routeId, double requestsPerSecond, double errorRate,
                                long p50, long p95, long p99, double rateLimitedPerSecond) {
}
