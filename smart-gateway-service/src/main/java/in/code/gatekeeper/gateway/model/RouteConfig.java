package in.code.gatekeeper.gateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Set;

/**
 * One exact endpoint of an organization. pathPattern is a template like /orders/{id}: {name} matches exactly one
 * path segment. allowedHeaders are lowercase; Content-Type and Accept are always forwarded on top of them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteConfig(String routeId, String pathPattern, String targetBaseUrl, String targetPathPrefix,
                          Set<String> allowedMethods, Set<String> allowedHeaders,
                          Integer requestsPerMinute, Integer perUserRequestsPerMinute,
                          int responseTimeoutMs, boolean idempotencyEnabled, List<HeaderRule> headerRules) {

    public RouteConfig {
        allowedMethods = allowedMethods == null ? Set.of() : Set.copyOf(allowedMethods);
        allowedHeaders = allowedHeaders == null ? Set.of() : Set.copyOf(allowedHeaders);
        headerRules = headerRules == null ? List.of() : List.copyOf(headerRules);
        targetPathPrefix = targetPathPrefix == null ? "" : targetPathPrefix;
    }
}
