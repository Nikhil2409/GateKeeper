package in.code.gatekeeper.management.dto.requestDto;

import in.code.gatekeeper.management.enums.HttpMethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

/**
 * One exact endpoint. A request to {gateway}/{orgSlug}{path} matching pathPattern is forwarded to
 * targetBaseUrl + targetPathPrefix + path. {name} matches exactly one path segment; there are no wildcards.
 * Example: pathPattern=/orders/{id}, targetBaseUrl=http://orders-svc:8080, targetPathPrefix=/api/v1
 *          GET /acme/orders/42  ->  http://orders-svc:8080/api/v1/orders/42
 */
public record RouteRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,99}$",
                message = "must be 2-100 lowercase letters, digits or hyphens") String routeId,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 500) @Pattern(regexp = "^(/([A-Za-z0-9_~-][A-Za-z0-9._~-]*|\\{[a-zA-Z][a-zA-Z0-9]*}))+$",
                message = "must be an exact path like /orders or /orders/{id} (no wildcards, no . or .. segments)") String pathPattern,
        @NotBlank @Size(max = 1000) @Pattern(regexp = "^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$",
                message = "must be http(s)://host[:port] without a path") String targetBaseUrl,
        @Size(max = 500) @Pattern(regexp = "^(/[A-Za-z0-9._~-]+)*$",
                message = "must be empty or look like /api/v1") String targetPathPrefix,
        @NotEmpty Set<HttpMethodType> allowedMethods,
        @Positive @Max(1_000_000) Integer requestsPerMinute,
        @NotNull @Min(100) @Max(60_000) Integer responseTimeoutMs,
        Boolean idempotencyEnabled,
        List<@Valid HeaderRuleRequest> headerRules,
        Set<@Pattern(regexp = "^[A-Za-z0-9-]{1,200}$", message = "must be a valid header name") String> allowedHeaders) {
}
