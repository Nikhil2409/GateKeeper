package in.code.gatekeeper.gateway.util;

import in.code.gatekeeper.gateway.client.UpstreamClient;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Calls the upstream through the route's circuit breaker, retrying within the route's timeout budget.
 * GET/HEAD/OPTIONS and requests carrying an idempotency key on an idempotent route (the backend dedupes, #62) are
 * retried on connection failures, timeouts and 502/503/504; every other request only when it was never sent.
 */
@Slf4j
@Component
public class UpstreamCaller {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(502, 503, 504);
    private static final long MIN_ATTEMPT_MS = 100;

    private final UpstreamClient upstreamClient;
    private final GatewayMetrics metrics;
    private final CircuitBreakerRegistry breakers;
    private final long openSeconds;
    private final int maxAttempts;
    private final long backoffMs;

    public UpstreamCaller(UpstreamClient upstreamClient, GatewayMetrics metrics,
                          @Value("${gateway.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
                          @Value("${gateway.circuit-breaker.sliding-window-size}") int slidingWindowSize,
                          @Value("${gateway.circuit-breaker.minimum-calls}") int minimumCalls,
                          @Value("${gateway.circuit-breaker.open-seconds}") long openSeconds,
                          @Value("${gateway.circuit-breaker.half-open-calls}") int halfOpenCalls,
                          @Value("${gateway.retry.max-attempts}") int maxAttempts,
                          @Value("${gateway.retry.backoff-ms}") long backoffMs) {
        this.upstreamClient = upstreamClient;
        this.metrics = metrics;
        this.breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(openSeconds))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .build());
        metrics.bind(breakers);
        this.openSeconds = openSeconds;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
    }

    public HttpResponse<InputStream> call(OrgRoutes organization, RouteConfig route, String method, URI target,
                                          Map<String, List<String>> headers, byte[] body, boolean idempotent) {
        CircuitBreaker breaker = breakers.circuitBreaker(breakerName(organization.organizationId(), route.routeId()));
        boolean safe = idempotent || SAFE_METHODS.contains(method);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(route.responseTimeoutMs());

        for (int attempt = 1; ; attempt++) {
            if (!breaker.tryAcquirePermission()) {
                throw GatewayException.circuitOpen(openSeconds);
            }
            long start = System.nanoTime();
            int timeoutMs = (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - start));
            HttpResponse<InputStream> response;
            try {
                response = upstreamClient.send(method, target, headers, body, timeoutMs);
            } catch (GatewayException e) {
                breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
                if ((e.isNotSent() || safe) && canRetry(attempt, deadline)) {
                    log.info("Retrying {} {} after {} (attempt {})", method, route.routeId(), e.getReason(), attempt);
                    metrics.retry(route.routeId(), e.getReason());
                    pause(attempt);
                    continue;
                }
                throw e;
            }

            int status = response.statusCode();
            if (status < 500) {
                breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return response;
            }
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, new UpstreamServerError(status));
            if (safe && RETRYABLE_STATUSES.contains(status) && canRetry(attempt, deadline)) {
                log.info("Retrying {} {} after upstream {} (attempt {})", method, route.routeId(), status, attempt);
                metrics.retry(route.routeId(), "UPSTREAM_" + status);
                discard(response);
                pause(attempt);
                continue;
            }
            return response;
        }
    }

    // Route ids of this organization whose breaker is not closed (open or probing)
    public List<String> openCircuits(Long organizationId) {
        String prefix = organizationId + ":";
        return breakers.getAllCircuitBreakers().stream()
                .filter(breaker -> breaker.getName().startsWith(prefix))
                .filter(breaker -> breaker.getState() != CircuitBreaker.State.CLOSED)
                .map(breaker -> breaker.getName().substring(prefix.length()))
                .sorted()
                .toList();
    }

    private boolean canRetry(int attempt, long deadline) {
        long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        return attempt < maxAttempts && remainingMs > backoff(attempt) + MIN_ATTEMPT_MS;
    }

    private long backoff(int attempt) {
        return backoffMs << (attempt - 1);
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(backoff(attempt) + ThreadLocalRandom.current().nextLong(backoffMs / 2 + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GatewayException.upstreamFailed();
        }
    }

    private static void discard(HttpResponse<InputStream> response) {
        try (InputStream ignored = response.body()) {
            // closing is enough
        } catch (IOException ignored) {
            // the response is thrown away anyway
        }
    }

    private static String breakerName(Long organizationId, String routeId) {
        return organizationId + ":" + routeId;
    }

    private static class UpstreamServerError extends RuntimeException {
        UpstreamServerError(int status) {
            super("upstream answered " + status, null, false, false);
        }
    }
}
