package in.code.gatekeeper.gateway.controller;

import in.code.gatekeeper.gateway.check.HeaderFilter;
import in.code.gatekeeper.gateway.check.RequestGuard;
import in.code.gatekeeper.gateway.check.RequestGuard.PublicPath;
import in.code.gatekeeper.gateway.check.UpstreamTargetGuard;
import in.code.gatekeeper.gateway.client.UpstreamClient;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.registry.RouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.UUID;

/**
 * Every tenant request: /{organizationSlug}/{route path}. The gateway's own /_gateway endpoints are more specific
 * mappings and win. Each step is its own class, run in this order:
 *
 *   RequestGuard -> organization -> RouteMatcher -> [token check] -> [quota, rate limit, idempotency]
 *   -> UpstreamTargetGuard -> HeaderFilter -> UpstreamClient -> HeaderFilter (response)
 */
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private final RequestGuard requestGuard;
    private final RouteRegistry routeRegistry;
    private final UpstreamTargetGuard upstreamTargetGuard;
    private final HeaderFilter headerFilter;
    private final UpstreamClient upstreamClient;

    @RequestMapping("/{slug}/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestId = UUID.randomUUID().toString();
        response.setHeader(HeaderFilter.REQUEST_ID, requestId);   // on every answer, errors included

        PublicPath path = requestGuard.parsePath(request.getRequestURI(), request.getQueryString());
        OrgRoutes organization = routeRegistry.find(path.slug())
                .filter(OrgRoutes::isActive)
                .orElseThrow(GatewayException::notFound);
        RouteConfig route = organization.matcher().match(path.segments())
                .orElseThrow(GatewayException::notFound);
        if (!route.allowedMethods().contains(request.getMethod())) {
            throw GatewayException.methodNotAllowed(route.allowedMethods());
        }
        byte[] body = requestGuard.readBody(request);

        // TODO: verify the user token with organization.tokenSettings()
        // TODO: per-user quota, per-route rate limit, idempotency (Redis)

        URI target = targetUri(route, path);
        upstreamTargetGuard.check(target);
        HttpResponse<InputStream> upstream = upstreamClient.send(request.getMethod(), target,
                headerFilter.requestHeaders(request, route, requestId), body, route.responseTimeoutMs());

        response.setStatus(upstream.statusCode());
        headerFilter.responseHeaders(upstream.headers(), route)
                .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            in.transferTo(out);
        }
    }

    // targetBaseUrl + targetPathPrefix + the request path exactly as received (+ query)
    private static URI targetUri(RouteConfig route, PublicPath path) {
        String target = route.targetBaseUrl() + route.targetPathPrefix() + path.path()
                + (path.rawQuery() == null ? "" : "?" + path.rawQuery());
        try {
            return URI.create(target);
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest("Invalid characters in path or query");
        }
    }
}
