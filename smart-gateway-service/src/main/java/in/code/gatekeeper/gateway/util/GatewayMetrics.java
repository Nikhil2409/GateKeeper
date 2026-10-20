package in.code.gatekeeper.gateway.util;

import in.code.gatekeeper.gateway.model.TrafficEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;

/**
 * The gateway's own Prometheus metrics. gateway_requests_seconds has one series per organization (slug for people,
 * id for analytic-service's tenant-scoped queries), route, status and outcome (FORWARDED or the reject reason) with
 * a histogram, so Grafana can draw rate, error rate, 429s and p99.
 */
@Component
@RequiredArgsConstructor
public class GatewayMetrics {

    private static final String NONE = "none";

    private final MeterRegistry registry;

    public void request(TrafficEvent event) {
        Timer.builder("gateway.requests")
                .description("Tenant requests handled by the gateway")
                .tag("organization", orNone(event.organizationSlug()))
                .tag("organization_id", event.organizationId() == null ? NONE : String.valueOf(event.organizationId()))
                .tag("route", orNone(event.routeId()))
                .tag("status", String.valueOf(event.status()))
                .tag("outcome", event.rejectReason() == null ? "FORWARDED" : event.rejectReason())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry)
                .record(Duration.ofMillis(event.latencyMs()));
    }

    public void retry(String routeId, String cause) {
        Counter.builder("gateway.upstream.retries").tag("route", routeId).tag("cause", cause)
                .register(registry).increment();
    }

    // a check that ran without Redis (limits counted locally, idempotency bypassed)
    public void redisFallback(String check) {
        Counter.builder("gateway.redis.fallback").tag("check", check).register(registry).increment();
    }

    public void droppedTrafficEvent() {
        Counter.builder("gateway.traffic.events.dropped").register(registry).increment();
    }

    public void trafficEventQueue(BlockingQueue<?> queue) {
        registry.gauge("gateway.traffic.events.queued", queue, BlockingQueue::size);
    }

    // resilience4j_circuitbreaker_state{name="orgId:routeId", state="open"} and friends
    public void bind(CircuitBreakerRegistry breakers) {
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
    }

    private static String orNone(String value) {
        return value == null ? NONE : value;
    }
}
