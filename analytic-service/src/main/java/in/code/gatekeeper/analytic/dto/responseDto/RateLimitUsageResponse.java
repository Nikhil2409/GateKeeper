package in.code.gatekeeper.analytic.dto.responseDto;

/**
 * A route with a rate limit: requests counted in the current 1-minute window (the gateway's window) and the 429s
 * it answered in the last hour.
 */
public record RateLimitUsageResponse(String organizationSlug, String routeId, int limitPerMinute,
                                     long usedThisWindow, long rejectedLastHour) {
}
