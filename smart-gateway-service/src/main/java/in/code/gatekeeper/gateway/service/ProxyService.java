package in.code.gatekeeper.gateway.service;

import in.code.gatekeeper.gateway.check.HeaderFilter;
import in.code.gatekeeper.gateway.check.IdempotencyCheck;
import in.code.gatekeeper.gateway.check.IdempotencyCheck.Ticket;
import in.code.gatekeeper.gateway.check.QuotaCheck;
import in.code.gatekeeper.gateway.check.RateLimitCheck;
import in.code.gatekeeper.gateway.check.RequestGuard;
import in.code.gatekeeper.gateway.check.RequestGuard.PublicPath;
import in.code.gatekeeper.gateway.check.TokenCheck;
import in.code.gatekeeper.gateway.check.UpstreamTargetGuard;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.filter.TrafficEventFilter;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.util.ResponseWriter;
import in.code.gatekeeper.gateway.util.RouteResolver;
import in.code.gatekeeper.gateway.util.RouteResolver.ResolvedRoute;
import in.code.gatekeeper.gateway.util.UpstreamCaller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;

@Service
@RequiredArgsConstructor
public class ProxyService {

    private final RequestGuard requestGuard;
    private final RouteResolver routeResolver;
    private final TokenCheck tokenCheck;
    private final QuotaCheck quotaCheck;
    private final RateLimitCheck rateLimitCheck;
    private final UpstreamTargetGuard upstreamTargetGuard;
    private final IdempotencyCheck idempotencyCheck;
    private final HeaderFilter headerFilter;
    private final UpstreamCaller upstreamCaller;
    private final ResponseWriter responseWriter;

    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestId = (String) request.getAttribute(TrafficEventFilter.REQUEST_ID);

        PublicPath path = requestGuard.parsePath(request.getRequestURI(), request.getQueryString());
        ResolvedRoute resolved = routeResolver.resolve(path, request);
        OrgRoutes organization = resolved.organization();
        RouteConfig route = resolved.route();

        String userId = tokenCheck.check(organization, request);
        byte[] body = requestGuard.readBody(request);

        quotaCheck.check(organization, route, userId);
        rateLimitCheck.check(organization, route);

        URI target = upstreamTargetGuard.resolve(route, path);
        Ticket ticket = idempotencyCheck.start(organization, route, userId, request, body);
        if (ticket != null && ticket.replay() != null) {
            responseWriter.writeReplay(response, ticket.replay());
            return;
        }

        HttpResponse<InputStream> upstream;
        try {
            upstream = upstreamCaller.call(organization, route, request.getMethod(), target,
                    headerFilter.requestHeaders(request, route, requestId, userId), body, ticket != null);
        } catch (GatewayException e) {
            if (ticket == null) {
                throw e;
            }
            idempotencyCheck.upstreamFailed(ticket);
            throw e.withSafeRetryHint();
        }
        responseWriter.writeUpstream(response, upstream, route, ticket);
    }
}
