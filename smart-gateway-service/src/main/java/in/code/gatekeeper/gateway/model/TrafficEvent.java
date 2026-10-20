package in.code.gatekeeper.gateway.model;

import java.time.Instant;

/**
 * One tenant request as it went through the gateway, sent to analytic-service (Kafka topic gateway.traffic-events).
 * Forwarded and rejected requests alike, so analytics sees 404s, 401s and 429s too.
 *
 * organizationId/organizationSlug/routeId: null when the request never matched them (e.g. unknown slug).
 * path: the raw public path without the query string (queries can carry secrets).
 * rejectReason: null when forwarded upstream (status is then the upstream's), otherwise the gateway's reason code,
 *   e.g. NOT_FOUND, UNAUTHORIZED, RATE_LIMITED, QUOTA_EXCEEDED, BAD_GATEWAY, GATEWAY_TIMEOUT.
 * idempotencyOutcome: null when the check did not apply; EXECUTED, REPLAYED, IN_PROGRESS_CONFLICT or BODY_MISMATCH.
 * idempotencyKey: masked (first and last 4 characters), never the full key.
 * correlationId: the caller's X-Correlation-Id, or the request id when it sent none.
 */
public record TrafficEvent(String requestId, String correlationId, Instant timestamp, String gatewayInstanceId,
                           Long organizationId, String organizationSlug, String routeId,
                           String method, String path, int status, long latencyMs, String clientIp,
                           String rejectReason, String idempotencyOutcome, String idempotencyKey) {
}
