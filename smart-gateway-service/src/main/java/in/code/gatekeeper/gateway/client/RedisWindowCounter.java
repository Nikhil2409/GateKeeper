package in.code.gatekeeper.gateway.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Fixed 1-minute window counters in Redis, shared by every gateway instance. One key per counter and window
 * (e.g. rate:12:get-order:1760799600); it expires shortly after its window ends, so nothing has to be cleaned up.
 * Quota and rate limit use the same windows, so both reset at the same moment.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisWindowCounter {

    private static final long WINDOW_SECONDS = 60;
    private static final String EXPIRE_AFTER_SECONDS = String.valueOf(WINDOW_SECONDS + 10);

    // INCR and EXPIRE in one atomic step: a crash in between can never leave a counter that never expires
    private static final RedisScript<Long> INCREMENT = RedisScript.of("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return count""", Long.class);

    private final StringRedisTemplate redis;

    // count includes this request; secondsLeft is what a rejected caller gets as Retry-After
    public record Window(long count, long secondsLeft) {
    }

    // Counts one request in the current window. Empty when Redis can't be reached (the caller decides what that means).
    public Optional<Window> hit(String counterKey) {
        long now = Instant.now().getEpochSecond();
        long windowStart = now - now % WINDOW_SECONDS;
        try {
            Long count = redis.execute(INCREMENT, List.of(counterKey + ":" + windowStart), EXPIRE_AFTER_SECONDS);
            return Optional.of(new Window(count == null ? 0 : count, windowStart + WINDOW_SECONDS - now));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for counter {}: {}", counterKey, e.getMessage());
            return Optional.empty();
        }
    }
}
