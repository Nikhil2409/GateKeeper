package in.code.gatekeeper.gateway.client;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether Redis answers, for /_gateway/health. Without Redis the gateway still serves (limits fail open) but
 * idempotent routes answer 503, so it is reported next to readiness instead of changing it.
 * Pinged at most once per interval: the health endpoint is public and polled by load balancers.
 */
@Component
@RequiredArgsConstructor
public class RedisStatus {

    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;

    private volatile String status;
    private volatile Instant checkedAt = Instant.EPOCH;

    public String status() {
        if (status == null || Instant.now().isAfter(checkedAt.plus(CHECK_INTERVAL))) {
            status = ping();
            checkedAt = Instant.now();
        }
        return status;
    }

    private String ping() {
        try (RedisConnection connection = redis.getRequiredConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping()) ? "UP" : "DOWN";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }
}
