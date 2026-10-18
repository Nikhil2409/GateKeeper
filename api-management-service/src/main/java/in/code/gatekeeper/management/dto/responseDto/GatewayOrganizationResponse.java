package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;

import java.util.List;

/**
 * The complete configuration of one organization, used for both the snapshot and change events.
 * The gateway keeps one version per organization: if configurationVersion is newer than what it holds, it replaces
 * everything it has for that organization. Suspended or deleted organizations are included with no routes, so the
 * gateway drops their routes and remembers the version (a late, older event can't bring them back).
 *
 * status: ACTIVE, SUSPENDED or DELETED. tokenSettings: null until the tenant configures them (requests then get 401).
 */
public record GatewayOrganizationResponse(Long organizationId, String organizationSlug, long configurationVersion,
                                          String status, TokenSettingsResponse tokenSettings,
                                          List<GatewayRouteResponse> routes) {

    public static GatewayOrganizationResponse from(Organization organization, OrganizationTokenSettings tokenSettings,
                                                   List<ApiRoute> activeRoutes) {
        boolean operational = organization.isOperational();
        return new GatewayOrganizationResponse(organization.getId(), organization.getSlug(),
                organization.getConfigurationVersion(),
                organization.isActive() ? organization.getStatus().name() : "DELETED",
                TokenSettingsResponse.from(tokenSettings),
                operational ? activeRoutes.stream().map(GatewayRouteResponse::from).toList() : List.of());
    }
}
