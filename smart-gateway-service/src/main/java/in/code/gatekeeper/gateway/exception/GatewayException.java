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

    public static GatewayException conflict(String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
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

    public static GatewayException gatewayTimeout() {
        return new GatewayException(HttpStatus.GATEWAY_TIMEOUT, "Upstream service did not respond in time");
    }
}
