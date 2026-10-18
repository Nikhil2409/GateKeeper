package in.code.gatekeeper.gateway.filter;

import in.code.gatekeeper.gateway.registry.RouteRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Until the configuration is loaded the gateway has nothing to route against, so every tenant request is answered
 * with 503 straight away instead of a misleading 404. The gateway's own /_gateway endpoints still respond.
 */
@Component
@RequiredArgsConstructor
public class NotReadyFilter extends OncePerRequestFilter {

    private static final String BODY = """
            {"type":"about:blank","title":"Service Unavailable","status":503,\
            "detail":"The gateway has no configuration yet. Retry later."}""";

    private final RouteRegistry routeRegistry;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return routeRegistry.isReady() || request.getRequestURI().startsWith("/_gateway");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        request.setAttribute(TrafficEventFilter.REJECT_REASON, "NOT_READY");
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(BODY);
    }
}
