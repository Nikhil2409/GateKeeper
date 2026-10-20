package in.code.gatekeeper.gateway.client;

import in.code.gatekeeper.gateway.client.RedisGuard.RedisUnavailableException;
import in.code.gatekeeper.gateway.util.GatewayMetrics;
import in.code.gatekeeper.gateway.util.LocalWindowCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Fixed 1-minute window counters in Redis, shared by every gateway instance. One key per counter and window
 * (e.g. rate:12:get-order:1760799600); it expires shortly after its window ends, so nothing has to be cleaned up.
 * Quota and rate limit use the same windows, so both reset at the same moment.
 *
 * While Redis is down each instance counts on its own (LocalWindowCounter) and a limit applies as this instance's
 * share of it (limit / gateway.cluster-size), so limits still hold approximately (decision #72).
 */
@Slf4j
@Component
public class RedisWindowCounter {

    private static final long WINDOW_SECONDS = 60;
    private static final String EXPIRE_AFTER_SECONDS = String.valueOf(WINDOW_SECONDS + 10);

    // INCR and EXPIRE in one atomic step: a crash in between can never leave a counter that never expires
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count""", Long.class);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final GatewayMetrics metrics;
    private final int clusterSize;
    private final LocalWindowCounter local = new LocalWindowCounter();
    private volatile boolean countingLocally;

    public RedisWindowCounter(StringRedisTemplate redis, RedisGuard guard, GatewayMetrics metrics,
                              @Value("${gateway.cluster-size}") int clusterSize) {
        this.redis = redis;
        this.guard = guard;
        this.metrics = metrics;
        this.clusterSize = Math.max(1, clusterSize);
    }

    // count includes this request; secondsLeft is what a rejected caller gets as Retry-After;
    // local = counted by this instance only (Redis down)
    public record Window(long count, long secondsLeft, boolean local) {
    }

    public Window hit(String counterKey) {
        long now = Instant.now().getEpochSecond();
        long windowStart = now - now % WINDOW_SECONDS;
        long secondsLeft = windowStart + WINDOW_SECONDS - now;
        try {
            Long count = guard.call(() ->
                    redis.execute(INCREMENT, List.of(counterKey + ":" + windowStart), EXPIRE_AFTER_SECONDS));
            if (countingLocally) {
                countingLocally = false;
                local.clear();
                log.info("Redis is back: limits are counted across all instances again");
            }
            return new Window(count == null ? 0 : count, secondsLeft, false);
        } catch (RedisUnavailableException e) {
            if (!countingLocally) {
                countingLocally = true;
                log.warn("Redis unavailable: counting limits on this instance ({} of the limit)", "1/" + clusterSize);
            }
            metrics.redisFallback("limits");
            return new Window(local.hit(counterKey, windowStart), secondsLeft, true);
        }
    }

    public boolean exceeds(Window window, long limit) {
        long allowed = window.local() ? Math.max(1, (limit + clusterSize - 1) / clusterSize) : limit;
        return window.count() > allowed;
    }
}
