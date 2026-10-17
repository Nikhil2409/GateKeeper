package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.enums.HttpMethodType;

import java.util.List;

/**
 * Everything the gateway needs to serve one route; the public path is /{organizationSlug}{pathPattern}.
 */
public record GatewayRouteResponse(Long organizationId, String organizationSlug, String routeId,
                                   String pathPattern, String targetBaseUrl, String targetPathPrefix,
                                   List<HttpMethodType> allowedMethods, Integer requestsPerMinute,
                                   int responseTimeoutMs, boolean idempotencyEnabled,
                                   List<HeaderRuleResponse> headerRules, long configurationVersion) {

    public static GatewayRouteResponse from(ApiRoute route) {
        return new GatewayRouteResponse(route.getOrganization().getId(), route.getOrganization().getSlug(),
                route.getRouteId(), route.getPathPattern(), route.getTargetBaseUrl(), route.getTargetPathPrefix(),
                route.getAllowedMethods().stream().sorted().toList(),
                route.getRequestsPerMinute(), route.getResponseTimeoutMs(), route.isIdempotencyEnabled(),
                route.getHeaderRules().stream().map(HeaderRuleResponse::from).toList(),
                route.getConfigurationVersion());
    }
}
