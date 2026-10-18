package in.code.gatekeeper.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * status: UP or NOT_READY (then reason says why, e.g. "Loading configuration from Kafka").
 * redis: UP or DOWN; reported next to readiness, it does not change it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HealthResponse(String status, String instanceId, String reason, String redis) {

    // for the controller's status code only, not part of the JSON
    @JsonIgnore
    public boolean isUp() {
        return "UP".equals(status);
    }
}
