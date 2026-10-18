package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.enums.HttpMethodType;

import java.time.LocalDateTime;
import java.util.List;

public record RouteResponse(String routeId, String name, String pathPattern, String targetBaseUrl,
                            String targetPathPrefix, List<HttpMethodType> allowedMethods,
                            Integer requestsPerMinute, int responseTimeoutMs, boolean idempotencyEnabled,
                            List<HeaderRuleResponse> headerRules, boolean active,
                            LocalDateTime dateCreated, LocalDateTime dateUpdated) {

    public static RouteResponse from(ApiRoute route) {
        return new RouteResponse(route.getRouteId(), route.getName(), route.getPathPattern(),
                route.getTargetBaseUrl(), route.getTargetPathPrefix(),
                route.getAllowedMethods().stream().sorted().toList(),
                route.getRequestsPerMinute(), route.getResponseTimeoutMs(), route.isIdempotencyEnabled(),
                route.getHeaderRules().stream().map(HeaderRuleResponse::from).toList(),
                route.isActive(), route.getDateCreated(), route.getDateUpdated());
    }
}
