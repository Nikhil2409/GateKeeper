package in.code.gatekeeper.management.kafka;

import in.code.gatekeeper.management.dto.responseDto.TokenSettingsResponse;
import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;

import java.util.List;

/**
 * The value of one message on gateway.org-config (key = organizationId): one organization's COMPLETE configuration.
 * Same shape for the startup publish and for every change, so the gateway has a single code path: if
 * configurationVersion is newer than what it holds, it replaces everything it has for that organization.
 * Suspended or deleted organizations are sent with no routes, so the gateway drops them and remembers the version
 * (a late, older message can't bring them back).
 *
 * organizationName is for analytic-service (display only); the gateway ignores it.
 * correlationId: the admin request that caused the change ("startup" for the startup publish).
 * status: ACTIVE, SUSPENDED or DELETED. tokenSettings: null until the tenant configures them (requests then get 401).
 */
public record OrgConfigMessage(Long organizationId, String organizationSlug, String organizationName,
                               long configurationVersion,
                               String status, TokenSettingsResponse tokenSettings, List<RouteConfigMessage> routes,
                               String correlationId) {

    public static OrgConfigMessage from(Organization organization, OrganizationTokenSettings tokenSettings,
                                        List<ApiRoute> activeRoutes, String correlationId) {
        boolean operational = organization.isOperational();
        return new OrgConfigMessage(organization.getId(), organization.getSlug(), organization.getName(),
                organization.getConfigurationVersion(),
                organization.isActive() ? organization.getStatus().name() : "DELETED",
                TokenSettingsResponse.from(tokenSettings),
                operational ? activeRoutes.stream().map(RouteConfigMessage::from).toList() : List.of(),
                correlationId);
    }
}
