package in.code.gatekeeper.analytic.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A value on gateway.traffic-events, exactly as the gateway's TrafficEvent sends it. Unknown fields are ignored, so
 * the gateway can add fields without breaking this service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrafficEventMessage(String requestId, Instant timestamp, String gatewayInstanceId,
                                  Long organizationId, String organizationSlug, String routeId,
                                  String method, String path, int status, long latencyMs, String clientIp,
                                  String rejectReason, String idempotencyOutcome, String idempotencyKey) {
}
