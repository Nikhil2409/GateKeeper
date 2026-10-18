package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.filter.TrafficEventFilter;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
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
 * Fails CLOSED: if Redis is down the request gets 503, because forwarding could run a payment twice.
 */
@Slf4j
@Component
public class IdempotencyCheck {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,255}");
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;
    private final Duration completedTtl;
    @Getter
    private final int maxStoredResponseBytes;

    public IdempotencyCheck(StringRedisTemplate redis, JsonMapper jsonMapper,
                            @Value("${gateway.idempotency.ttl}") Duration completedTtl,
                            @Value("${gateway.idempotency.max-response-bytes}") int maxStoredResponseBytes) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
        this.completedTtl = completedTtl;
        this.maxStoredResponseBytes = maxStoredResponseBytes;
    }

    public record StoredResponse(String state, String fingerprint, int status, Map<String, List<String>> headers,
                                 byte[] body) {
    }

    // replay is set when the response is already known; otherwise this request owns the key and must complete/release
    public record Ticket(String redisKey, String fingerprint, StoredResponse replay) {
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
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, inProgress, lockTtl))) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "EXECUTED");
                return new Ticket(redisKey, fingerprint, null);
            }
            String existing = redis.opsForValue().get(redisKey);
            if (existing == null) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "IN_PROGRESS_CONFLICT");
                // expired between the two calls: let the client retry rather than racing for it here
                throw GatewayException.conflict("A request with this " + HEADER + " is still in progress");
            }
            StoredResponse stored = jsonMapper.readValue(existing, StoredResponse.class);
            if (!stored.fingerprint().equals(fingerprint)) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "BODY_MISMATCH");
                throw GatewayException.unprocessable(HEADER + " was already used for a different request");
            }
            if (!COMPLETED.equals(stored.state())) {
                request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "IN_PROGRESS_CONFLICT");
                throw GatewayException.conflict("A request with this " + HEADER + " is still in progress");
            }
            request.setAttribute(TrafficEventFilter.IDEMPOTENCY_OUTCOME, "REPLAYED");
            return new Ticket(redisKey, fingerprint, stored);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for idempotency key {}: {}", redisKey, e.getMessage());
            throw GatewayException.serviceUnavailable("Idempotency store is unavailable, retry later");
        }
    }

    public void complete(Ticket ticket, int status, Map<String, List<String>> headers, byte[] body) {
        try {
            String completed = jsonMapper.writeValueAsString(
                    new StoredResponse(COMPLETED, ticket.fingerprint(), status, headers, body));
            redis.opsForValue().set(ticket.redisKey(), completed, completedTtl);
        } catch (DataAccessException e) {
            // the caller still gets its response; a retry within the lock TTL gets 409, after it runs again
            log.warn("Could not store idempotent response for {}: {}", ticket.redisKey(), e.getMessage());
        }
    }

    // Frees the key so the client can retry, e.g. when the upstream was never reached
    public void release(Ticket ticket) {
        if (ticket == null || ticket.replay() != null) {
            return;
        }
        try {
            redis.delete(ticket.redisKey());
        } catch (DataAccessException e) {
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
