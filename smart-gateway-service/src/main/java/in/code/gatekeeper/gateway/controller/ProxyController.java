package in.code.gatekeeper.gateway.controller;

import in.code.gatekeeper.gateway.check.HeaderFilter;
import in.code.gatekeeper.gateway.check.IdempotencyCheck;
import in.code.gatekeeper.gateway.check.IdempotencyCheck.StoredResponse;
import in.code.gatekeeper.gateway.check.IdempotencyCheck.Ticket;
import in.code.gatekeeper.gateway.check.QuotaCheck;
import in.code.gatekeeper.gateway.check.RateLimitCheck;
import in.code.gatekeeper.gateway.check.RequestGuard;
import in.code.gatekeeper.gateway.check.RequestGuard.PublicPath;
import in.code.gatekeeper.gateway.check.TokenCheck;
import in.code.gatekeeper.gateway.check.UpstreamTargetGuard;
import in.code.gatekeeper.gateway.client.UpstreamClient;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.registry.RouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every tenant request: /{organizationSlug}/{route path}. The gateway's own /_gateway endpoints are more specific
 * mappings and win. Each step is its own class, run in this order:
 *
 *   RequestGuard -> organization -> RouteMatcher -> TokenCheck -> QuotaCheck -> RateLimitCheck
 *   -> UpstreamTargetGuard -> IdempotencyCheck -> HeaderFilter -> UpstreamClient -> HeaderFilter (response)
 */
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private final RequestGuard requestGuard;
    private final RouteRegistry routeRegistry;
    private final TokenCheck tokenCheck;
    private final QuotaCheck quotaCheck;
    private final RateLimitCheck rateLimitCheck;
    private final UpstreamTargetGuard upstreamTargetGuard;
    private final IdempotencyCheck idempotencyCheck;
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
        // the token is checked before the body is read, so unauthenticated callers can't make the gateway buffer 1 MB
        String userId = tokenCheck.check(organization, request);
        byte[] body = requestGuard.readBody(request);

        quotaCheck.check(organization, route, userId);
        rateLimitCheck.check(organization, route);

        URI target = targetUri(route, path);
        upstreamTargetGuard.check(target);

        Ticket ticket = idempotencyCheck.start(organization, route, userId, request, body);
        if (ticket != null && ticket.replay() != null) {
            writeReplay(response, ticket.replay());
            return;
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = upstreamClient.send(request.getMethod(), target,
                    headerFilter.requestHeaders(request, route, requestId, userId), body, route.responseTimeoutMs());
        } catch (GatewayException e) {
            // 502: the upstream never answered, so a retry is safe. 504: it may have run the request, so the key stays
            // locked until its TTL ends and an early retry gets 409 instead of a possible second execution.
            if (e.getStatus() != HttpStatus.GATEWAY_TIMEOUT) {
                idempotencyCheck.release(ticket);
            }
            throw e;
        }

        Map<String, List<String>> headers = headerFilter.responseHeaders(upstream.headers(), route);
        response.setStatus(upstream.statusCode());
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            if (ticket == null) {
                in.transferTo(out);
                return;
            }
            // buffer up to the limit so the response can be stored for replays; 5xx and oversized ones are not stored
            // (the key is released, so a retry runs again)
            byte[] start = in.readNBytes(idempotencyCheck.getMaxStoredResponseBytes() + 1);
            if (start.length <= idempotencyCheck.getMaxStoredResponseBytes() && upstream.statusCode() < 500) {
                idempotencyCheck.complete(ticket, upstream.statusCode(), headers, start);
            } else {
                idempotencyCheck.release(ticket);
            }
            out.write(start);
            in.transferTo(out);
        }
    }

    private static void writeReplay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        stored.headers().forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
        response.setHeader(IdempotencyCheck.REPLAYED_HEADER, "true");
        try (OutputStream out = response.getOutputStream()) {
            out.write(stored.body() == null ? new byte[0] : stored.body());
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
