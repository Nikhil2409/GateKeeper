package in.code.gatekeeper.analytic.dto.responseDto;

import java.time.Instant;
import java.util.List;

/**
 * storedKeys: executions within the TTL (each one left a stored response in the gateway's Redis).
 * recent: the latest replays and rejections in the last hour; key is masked by the gateway.
 */
public record IdempotencyStatsResponse(long ttlSeconds, long storedKeys, long replaysLastHour,
                                       long inProgressConflictsLastHour, long bodyMismatchesLastHour,
                                       List<RecentOutcome> recent) {

    public record RecentOutcome(String key, String organizationSlug, String routeId, String outcome, Instant at) {
    }
}
