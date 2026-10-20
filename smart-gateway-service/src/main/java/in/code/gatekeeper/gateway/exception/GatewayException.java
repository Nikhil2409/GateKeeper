package in.code.gatekeeper.gateway.exception;

import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Set;
import java.util.TreeSet;

/**
 * Every reason the gateway answers a request itself instead of forwarding it. The message goes to the caller, so it
 * never names internal hosts or addresses; details are logged instead. reason is a stable code for traffic events
 * (the status name, or something more specific such as RATE_LIMITED vs QUOTA_EXCEEDED for a 429).
 */
@Getter
public class GatewayException extends RuntimeException {

    private static final String UPSTREAM_UNREACHABLE = "UPSTREAM_UNREACHABLE";
    private static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    private final HttpStatus status;
    private final HttpHeaders headers;
    private final String reason;

    private GatewayException(HttpStatus status, String reason, String message, HttpHeaders headers) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.headers = headers;
    }

    private GatewayException(HttpStatus status, String message, HttpHeaders headers) {
        this(status, status.name(), message, headers);
    }

    private GatewayException(HttpStatus status, String message) {
        this(status, message, new HttpHeaders());
    }

    public static GatewayException badRequest(String message) {
        return new GatewayException(HttpStatus.BAD_REQUEST, message);
    }

    public static GatewayException unauthorized(String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return new GatewayException(HttpStatus.UNAUTHORIZED, message, headers);
    }

    // Unknown, suspended and deleted organizations all look the same, so outsiders can't probe which slugs exist
    public static GatewayException notFound() {
        return new GatewayException(HttpStatus.NOT_FOUND, "No route matches this request");
    }

    public static GatewayException methodNotAllowed(Set<String> allowedMethods) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, String.join(", ", new TreeSet<>(allowedMethods)));
        return new GatewayException(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed for this route", headers);
    }

    public static GatewayException payloadTooLarge(long maxBytes) {
        return new GatewayException(HttpStatus.CONTENT_TOO_LARGE, "Request body is larger than " + maxBytes + " bytes");
    }

    public static GatewayException conflict(String message, long retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, retryAfterSeconds)));
        return new GatewayException(HttpStatus.CONFLICT, message, headers);
    }

    public static GatewayException unprocessable(String message) {
        return new GatewayException(HttpStatus.UNPROCESSABLE_CONTENT, message);
    }

    // reason: RATE_LIMITED (route limit) or QUOTA_EXCEEDED (per-user quota)
    public static GatewayException tooManyRequests(String reason, String message, long retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return new GatewayException(HttpStatus.TOO_MANY_REQUESTS, reason, message, headers);
    }

    public static GatewayException serviceUnavailable(String message) {
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public static GatewayException badGateway(String message) {
        return new GatewayException(HttpStatus.BAD_GATEWAY, message);
    }

    // The connection was never made, so the upstream provably did not see the request
    public static GatewayException upstreamUnreachable() {
        return new GatewayException(HttpStatus.BAD_GATEWAY, UPSTREAM_UNREACHABLE, "Upstream service is not reachable",
                new HttpHeaders());
    }

    // The connection broke after the request went out: the upstream may have run it
    public static GatewayException upstreamFailed() {
        return new GatewayException(HttpStatus.BAD_GATEWAY, "UPSTREAM_FAILED", "Upstream service failed to answer",
                new HttpHeaders());
    }

    public static GatewayException gatewayTimeout() {
        return new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "Upstream service did not respond in time");
    }

    public static GatewayException circuitOpen(long retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, CIRCUIT_OPEN,
                "Upstream service is failing, try again later", headers);
    }

    // Same failure, telling the caller a retry with the same key is safe (the backend dedupes on it)
    public GatewayException withSafeRetryHint() {
        return new GatewayException(status, reason, getMessage() + ". It is safe to retry with the same Idempotency-Key",
                headers);
    }

    // Nothing reached the upstream
    public boolean isNotSent() {
        return UPSTREAM_UNREACHABLE.equals(reason) || CIRCUIT_OPEN.equals(reason);
    }
}
