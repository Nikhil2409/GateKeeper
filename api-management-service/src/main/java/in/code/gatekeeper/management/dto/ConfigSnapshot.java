package in.code.gatekeeper.management.dto;

import in.code.gatekeeper.management.dto.requestDto.HeaderRuleRequest;
import in.code.gatekeeper.management.dto.requestDto.RouteRequest;
import in.code.gatekeeper.management.dto.requestDto.TokenSettingsRequest;
import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;

import java.util.List;
import java.util.Set;

/**
 * An organization's whole tenant-owned configuration at one version, stored as JSON in organization_config_versions.
 * Routes are kept in request shape so a rollback re-applies them through the same checks as a normal save.
 * tokenSettings is null when none were active; routes include inactive ones.
 */
public record ConfigSnapshot(TokenSettingsRequest tokenSettings, List<RouteSnapshot> routes) {

    public record RouteSnapshot(RouteRequest route, boolean active) {
    }

    public static ConfigSnapshot from(List<ApiRoute> routes, OrganizationTokenSettings settings) {
        TokenSettingsRequest tokenSettings = settings == null ? null
                : new TokenSettingsRequest(settings.getIssuer(), settings.getJwksUri(), settings.getAudience());
        return new ConfigSnapshot(tokenSettings, routes.stream()
                .map(route -> new RouteSnapshot(new RouteRequest(route.getRouteId(), route.getName(),
                        route.getPathPattern(), route.getTargetBaseUrl(), route.getTargetPathPrefix(),
                        Set.copyOf(route.getAllowedMethods()), route.getRequestsPerMinute(),
                        route.getResponseTimeoutMs(), route.isIdempotencyEnabled(),
                        route.getHeaderRules().stream()
                                .map(rule -> new HeaderRuleRequest(rule.getDirection(), rule.getAction(),
                                        rule.getHeaderName(), rule.getHeaderValue()))
                                .toList(),
                        Set.copyOf(route.getAllowedHeaders())), route.isActive()))
                .toList());
    }

    public long activeRoutes() {
        return routes.stream().filter(RouteSnapshot::active).count();
    }
}
