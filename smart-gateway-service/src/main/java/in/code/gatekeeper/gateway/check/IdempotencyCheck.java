package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.client.RedisGuard;
import in.code.gatekeeper.gateway.client.RedisGuard.RedisUnavailableException;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.filter.TrafficEventFilter;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.util.GatewayMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * On routes with idempotencyEnabled, a retried write (same Idempotency-Key) gets the first response again instead
 * of running twice upstream. One Redis record per key, scoped to organization, route and user:
 *
 *   1. first request : SET NX an IN_PROGRESS record (with a fingerprint of the request) -> forward upstream
 *   2. upstream done : replace it with the COMPLETED response (status, headers, body), kept for the configured TTL
 *   3. a repeat      : COMPLETED + same fingerprint -> replay; IN_PROGRESS -> 409; other fingerprint -> 422
 *
 * Only unsafe methods are checked (GET/HEAD/OPTIONS are idempotent by definition) and the key is then required.
 * While Redis is down the check is BYPASSED (decision #72): the request goes on with its Idempotency-Key and the
 * backend's own deduplication (the contract of an idempotent route, #62) is the protection; no replay, no 409.
 * The answer carries Idempotency-Status: unchecked and the traffic event the outcome BYPASSED.
 */
@Slf4j
@Component
public class IdempotencyCheck {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    public static final String STATUS_HEADER = "Idempotency-Status";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final GatewayMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Duration completedTtl;
    @Getter
    private final int maxStoredResponseBytes;

    public IdempotencyCheck(StringRedisTemplate redis, RedisGuard guard, GatewayMetrics metrics,
                            JsonMapper jsonMapper,
                            @Value("${gateway.idempotency.ttl}") Duration completedTtl,
                            @Value("${gateway.idempotency.max-response-bytes}") int maxStoredResponseBytes) {
        this.redis = redis;
        this.guard = guard;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.completedTtl = completedTtl;
        this.maxStoredResponseBytes = maxStoredResponseBytes;
    }

    public record StoredResponse(String state, String fingerprint, int status, Map<String, List<String>> headers,
                                 byte[] body) {
    }

    // replay is set when the response is already known; otherwise this request owns the key and must complete/release.
    // No redisKey = bypassed (Redis down): nothing to complete or release
    public record Ticket(String redisKey, String fingerprint, StoredResponse replay) {

        static final Ticket BYPASSED = new Ticket(null, null, null);

        public boolean bypassed() {
            return redisKey == null;
        }
    }

    // null when the check does not apply to this request
    public Ticket start(OrgRoutes organization, RouteConfig route, String userId, HttpServletRequest request,
                        byte[] body) {
        if (!route.idempotencyEnabled() || SAFE_METHODS.contains(request.getMethod())) {
            return null;
        }
        String key = request.getHeader(HEADER);
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw GatewayException.badRequest(HEADER + " header (1-255 visible characters) is required for this route");
        }
        String redisKey = "idem:" + organization.organizationId() + ":" + route.routeId() + ":"
                + (userId == null ? "-" : userId) + ":" + key;
        request.setAttribute(TrafficEventFilter.IDEMPOTENCY_KEY, mask(key));
        String fingerprint = fingerprint(request, body);
        // held while the upstream call runs; if this gateway dies mid-call the key frees itself
        Duration lockTtl = Duration.ofMillis(route.responseTimeoutMs()).plusSeconds(10);

        try {
            String inProgress = jsonMapper.writeValueAsString(
                    new StoredResponse(IN_PROGRESS, fingerprint, 0, Map.of(), null));
            Boolean acquired = guard.call(() -> redis.opsForValue().setIfAbsent(redisKey, inProgress, lockTtl));
            if (Boolean.TRUE.equals(acquired)) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "EXECUTED");
                return new Ticket(redisKey, fingerprint, null);
            }
            String existing = guard.call(() -> redis.opsForValue().get(redisKey));
            if (existing == null) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "IN_PROGRESS_CONFLICT");
                // expired between the two calls: let the client retry rather than racing for it here
                throw GatewayException.conflict("A request with this " + HEADER + " is still in progress", 1);
            }
            StoredResponse stored = jsonMapper.readValue(existing, StoredResponse.class);
            if (!stored.fingerprint().equals(fingerprint)) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "BODY_MISMATCH");
                throw GatewayException.unprocessable(HEADER + " was already used for a different request");
            }
            if (!COMPLETED.equals(stored.state())) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "IN_PROGRESS_CONFLICT");
                throw GatewayException.conflict("A request with this " + HEADER + " is still in progress",
                        secondsLeft(redisKey));
            }
            request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "REPLAYED");
            return new Ticket(redisKey, fingerprint, stored);
        } catch (RedisUnavailableException e) {
            request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "BYPASSED");
            metrics.redisFallback("idempotency");
            return Ticket.BYPASSED;
        }
    }

    public void complete(Ticket ticket, int status, Map<String, List<String>> headers, byte[] body) {
        if (ticket.bypassed()) {
            return;
        }
        try {
            String completed = jsonMapper.writeValueAsString(
                    new StoredResponse(COMPLETED, ticket.fingerprint(), status, headers, body));
            guard.run(() -> redis.opsForValue().set(ticket.redisKey(), completed, completedTtl));
        } catch (RedisUnavailableException e) {
            // the caller still gets its response; a retry within the lock TTL gets 409, after it runs again
            log.warn("Could not store idempotent response for {}: {}", ticket.redisKey(), e.getMessage());
        }
    }

    // Any failure frees the key: an idempotent route's backend dedupes on the forwarded key (#62), so a retry is
    // safe even if the first attempt reached it
    public void upstreamFailed(Ticket ticket) {
        release(ticket);
    }

    private long secondsLeft(String redisKey) {
        try {
            Long seconds = guard.call(() -> redis.getExpire(redisKey));
            return seconds == null || seconds < 1 ? 1 : seconds;
        } catch (RedisUnavailableException e) {
            return 1;
        }
    }

    // Frees the key so the client can retry, e.g. when the upstream was never reached
    public void release(Ticket ticket) {
        if (ticket == null || ticket.replay() != null || ticket.bypassed()) {
            return;
        }
        try {
            guard.call(() -> redis.delete(ticket.redisKey()));
        } catch (RedisUnavailableException e) {
            log.warn("Could not release idempotency key {}: {}", ticket.redisKey(), e.getMessage());
        }
    }

    // Analytics only ever sees the edges of a key: enough to recognise it, useless to replay it
    private static String mask(String key) {
        return key.length() <= 8 ? "…" : key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    // Same key with a different method, path, query or body is a client bug, not a retry
    private static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update((request.getMethod() + " " + request.getRequestURI() + "?" + request.getQueryString() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            sha256.update(body);
            return HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
