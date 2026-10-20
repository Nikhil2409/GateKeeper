package in.code.gatekeeper.gateway.filter;

import in.code.gatekeeper.gateway.client.TrafficEventPublisher;
import in.code.gatekeeper.gateway.model.TrafficEvent;
import in.code.gatekeeper.gateway.util.GatewayMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The outermost step of every tenant request: gives it its X-Request-Id, measures it, and publishes ONE traffic
 * event when it is done, whatever happened in between (forwarded, rejected, or not ready yet).
 * The steps inside only leave facts as request attributes (the keys below); this filter turns them into the event.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrafficEventFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";

    // Request attributes set along the pipeline
    public static final String REQUEST_ID = "gateway.requestId";
    public static final String CORRELATION_ID = "gateway.correlationId";
    public static final String ORGANIZATION_ID = "gateway.organizationId";
    public static final String ORGANIZATION_SLUG = "gateway.organizationSlug";
    public static final String ROUTE_ID = "gateway.routeId";
    public static final String REJECT_REASON = "gateway.rejectReason";
    public static final String IDEMPOTENCY_OUTCOME = "gateway.idempotencyOutcome";
    public static final String IDEMPOTENCY_KEY = "gateway.idempotencyKey";

    private static final int MAX_PATH_LENGTH = 2048;
    // a caller's id is reused only if it is short and plain, so it can't inject anything into logs or headers
    private static final Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final TrafficEventPublisher publisher;
    private final GatewayMetrics metrics;
    private final String instanceId;

    public TrafficEventFilter(TrafficEventPublisher publisher, GatewayMetrics metrics,
                              @Value("${gateway.instance-id}") String instanceId) {
        this.publisher = publisher;
        this.metrics = metrics;
        this.instanceId = instanceId;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/_gateway");   // the gateway's own endpoints are not traffic
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        String incoming = request.getHeader(CORRELATION_ID_HEADER);
        String correlationId = incoming != null && VALID_CORRELATION_ID.matcher(incoming).matches() ? incoming : requestId;
        request.setAttribute(REQUEST_ID, requestId);
        request.setAttribute(CORRELATION_ID, correlationId);
        response.setHeader(REQUEST_ID_HEADER, requestId);   // on every answer, errors included
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        MDC.put(MDC_CORRELATION_ID, correlationId);

        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            publishQuietly(request, response, requestId, correlationId, startedAt,
                    (System.nanoTime() - start) / 1_000_000, failed);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    // An analytics problem must never turn into a failed request
    private void publishQuietly(HttpServletRequest request, HttpServletResponse response, String requestId,
                                String correlationId, Instant startedAt, long latencyMs, boolean failed) {
        try {
            String path = request.getRequestURI();
            TrafficEvent event = new TrafficEvent(requestId, correlationId, startedAt, instanceId,
                    (Long) request.getAttribute(ORGANIZATION_ID), (String) request.getAttribute(ORGANIZATION_SLUG),
                    (String) request.getAttribute(ROUTE_ID), request.getMethod(),
                    path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) : path,
                    failed ? 500 : response.getStatus(), latencyMs, request.getRemoteAddr(),
                    failed ? "INTERNAL_ERROR" : (String) request.getAttribute(REJECT_REASON),
                    (String) request.getAttribute(IDEMPOTENCY_OUTCOME), (String) request.getAttribute(IDEMPOTENCY_KEY));
            metrics.request(event);
            publisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("Could not publish traffic event {}: {}", requestId, e.toString());
        }
    }
}
