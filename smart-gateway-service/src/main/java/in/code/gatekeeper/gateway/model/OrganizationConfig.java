package in.code.gatekeeper.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * One organization's complete configuration, exactly as api-management sends it in the snapshot (and, later,
 * in each Kafka event). status is ACTIVE, SUSPENDED or DELETED; non-active organizations arrive with no routes.
 * tokenSettings is null until the tenant configures them. correlationId: the admin request that caused the change.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrganizationConfig(Long organizationId, String organizationSlug, long configurationVersion,
                                 String status, TokenSettings tokenSettings, List<RouteConfig> routes,
                                 String correlationId) {

    public OrganizationConfig {
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
