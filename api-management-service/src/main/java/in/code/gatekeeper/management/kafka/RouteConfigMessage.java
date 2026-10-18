package in.code.gatekeeper.management.kafka;

import in.code.gatekeeper.management.dto.responseDto.HeaderRuleResponse;
import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.enums.HttpMethodType;

import java.util.List;

/**
 * Everything the gateway needs to serve one exact endpoint; the public path is /{organizationSlug}{pathPattern}.
 * Every tenant route requires a user token verified with the organization's token settings.
 */
public record RouteConfigMessage(String routeId, String pathPattern, String targetBaseUrl, String targetPathPrefix,
                                 List<HttpMethodType> allowedMethods, List<String> allowedHeaders,
                                 Integer requestsPerMinute, Integer perUserRequestsPerMinute,
                                 int responseTimeoutMs, boolean idempotencyEnabled,
                                 List<HeaderRuleResponse> headerRules) {

    public static RouteConfigMessage from(ApiRoute route) {
        return new RouteConfigMessage(route.getRouteId(), route.getPathPattern(), route.getTargetBaseUrl(),
                route.getTargetPathPrefix(), route.getAllowedMethods().stream().sorted().toList(),
                route.getAllowedHeaders().stream().sorted().toList(),
                route.getRequestsPerMinute(), route.getPerUserRequestsPerMinute(),
                route.getResponseTimeoutMs(), route.isIdempotencyEnabled(),
                route.getHeaderRules().stream().map(HeaderRuleResponse::from).toList());
    }
}
