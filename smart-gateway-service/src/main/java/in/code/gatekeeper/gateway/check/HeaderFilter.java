package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.model.HeaderRule;
import in.code.gatekeeper.gateway.model.RouteConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides which headers cross the gateway. Request: deny by default, only the route's allowlist (plus Content-Type
 * and Accept) reaches the upstream, then the tenant's REQUEST rules, then the gateway's own X-Forwarded-* and
 * X-Request-Id. Response: everything except connection-level headers, then the tenant's RESPONSE rules.
 * Header names are compared lowercase.
 */
@Component
public class HeaderFilter {

    public static final String REQUEST_ID = "x-request-id";

    private static final Set<String> ALWAYS_FORWARDED = Set.of("content-type", "accept");

    // Connection-level headers: they describe one hop, never the message, so they are never copied
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    // Set by the JDK HTTP client itself (it refuses them) or by the gateway; tenant rules can't touch these either
    private static final Set<String> GATEWAY_MANAGED = Set.of("host", "content-length", "expect",
            "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", REQUEST_ID);

    public Map<String, List<String>> requestHeaders(HttpServletRequest request, RouteConfig route, String requestId) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            boolean allowed = route.allowedHeaders().contains(lower) || ALWAYS_FORWARDED.contains(lower);
            if (allowed && !HOP_BY_HOP.contains(lower) && !GATEWAY_MANAGED.contains(lower)) {
                headers.put(lower, new ArrayList<>(Collections.list(request.getHeaders(name))));
            }
        }
        applyRules(headers, route.headerRules(), "REQUEST");

        // behind a load balancer this would append to its X-Forwarded-For instead (later, with a trusted-proxy list)
        headers.put("x-forwarded-for", List.of(request.getRemoteAddr()));
        headers.put("x-forwarded-proto", List.of(request.getScheme()));
        if (request.getHeader("host") != null) {
            headers.put("x-forwarded-host", List.of(request.getHeader("host")));
        }
        headers.put(REQUEST_ID, List.of(requestId));
        return headers;
    }

    public Map<String, List<String>> responseHeaders(HttpHeaders upstream, RouteConfig route) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        upstream.map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            // ":status" is an HTTP/2 pseudo-header the JDK client may expose
            if (!HOP_BY_HOP.contains(lower) && !lower.startsWith(":") && !lower.equals(REQUEST_ID)) {
                headers.put(lower, new ArrayList<>(values));
            }
        });
        applyRules(headers, route.headerRules(), "RESPONSE");
        return headers;
    }

    private static void applyRules(Map<String, List<String>> headers, List<HeaderRule> rules, String direction) {
        for (HeaderRule rule : rules) {
            String name = rule.headerName().toLowerCase(Locale.ROOT);
            if (!direction.equals(rule.direction()) || HOP_BY_HOP.contains(name) || GATEWAY_MANAGED.contains(name)) {
                continue;
            }
            switch (rule.action()) {
                case "ADD" -> headers.computeIfAbsent(name, key -> new ArrayList<>()).add(rule.headerValue());
                case "SET" -> headers.put(name, new ArrayList<>(List.of(rule.headerValue())));
                case "REMOVE" -> headers.remove(name);
                default -> { }   // unknown action from a newer api-management: ignore rather than fail the request
            }
        }
    }
}
