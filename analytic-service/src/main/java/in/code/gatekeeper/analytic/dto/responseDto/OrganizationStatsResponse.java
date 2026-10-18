package in.code.gatekeeper.analytic.dto.responseDto;

// One organization's traffic in the range (platform admins only)
public record OrganizationStatsResponse(Long organizationId, String organizationSlug, String organizationName,
                                        long requests, double errorRate, double serverErrorRate, long p50, long p95,
                                        long rateLimited, long replays) {
}
