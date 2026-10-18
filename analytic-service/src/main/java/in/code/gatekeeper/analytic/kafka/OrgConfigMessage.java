package in.code.gatekeeper.analytic.kafka;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A value on gateway.org-config as api-management publishes it, reduced to what analytics needs
 * (name for display, route limits for the traffic-policy views). Everything else is ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrgConfigMessage(Long organizationId, String organizationSlug, String organizationName,
                               long configurationVersion, String status, List<Route> routes) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(String routeId, Integer requestsPerMinute, Integer perUserRequestsPerMinute,
                        boolean idempotencyEnabled) {
    }
}
