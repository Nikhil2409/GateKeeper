package in.code.gatekeeper.gateway.client;

import in.code.gatekeeper.gateway.util.GatewayMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Every Redis call of the request path goes through here (decision #72). After a few failed calls the breaker
 * opens and callers get RedisUnavailableException at once instead of each waiting for the Redis timeout. Requests
 * never probe: a background ping every few seconds closes the breaker as soon as Redis answers again. Per gateway
 * instance, shown as resilience4j_circuitbreaker_state{name="redis"}.
 */
@Slf4j
@Component
public class RedisGuard {

    public static class RedisUnavailableException extends RuntimeException {
        RedisUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final CircuitBreaker breaker;
    private final StringRedisTemplate redis;
    private final ScheduledExecutorService prober = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("redis-prober").factory());

    public RedisGuard(StringRedisTemplate redis, GatewayMetrics metrics,
                      @Value("${gateway.redis.probe-seconds}") int probeSeconds) {
        this.redis = redis;
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                // only the prober closes it (below), so no request ever waits as a probe
                .waitDurationInOpenState(Duration.ofDays(365))
                .build());
        metrics.bind(registry);
        this.breaker = registry.circuitBreaker("redis");
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Redis circuit breaker {}", event.getStateTransition()));
        prober.scheduleWithFixedDelay(this::probe, probeSeconds, probeSeconds, TimeUnit.SECONDS);
    }

    private void probe() {
        if (breaker.getState() != CircuitBreaker.State.OPEN) {
            return;
        }
        try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
            if ("PONG".equals(connection.ping())) {
                breaker.transitionToClosedState();
            }
        } catch (RuntimeException e) {
            // still down; try again next time
        }
    }

    @PreDestroy
    void shutdown() {
        prober.shutdownNow();
    }

    public <T> T call(Supplier<T> redisCall) {
        if (!breaker.tryAcquirePermission()) {
            throw new RedisUnavailableException("Redis circuit breaker is open", null);
        }
        long start = System.nanoTime();
        try {
            T result = redisCall.get();
            breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (DataAccessException e) {
            breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw new RedisUnavailableException(e.getMessage(), e);
        }
    }

    public void run(Runnable redisCall) {
        call(() -> {
            redisCall.run();
            return null;
        });
    }
}
