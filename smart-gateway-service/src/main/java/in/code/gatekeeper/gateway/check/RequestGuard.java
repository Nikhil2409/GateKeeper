package in.code.gatekeeper.gateway.check;

import in.code.gatekeeper.gateway.exception.GatewayException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The first check on every tenant request, before any routing. Works on the RAW (still percent-encoded) path, the
 * same text that is later forwarded, so what is matched is exactly what the upstream receives. Rejects every trick
 * for slipping past exact matching: dot segments, empty segments, encoded slashes or backslashes, control characters.
 */
@Component
public class RequestGuard {

    private static final int MAX_PATH_LENGTH = 2048;

    private final long maxBodyBytes;

    public RequestGuard(@Value("${gateway.request.max-body-bytes}") long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public record PublicPath(String slug, List<String> segments, String path, String rawQuery) {
    }

    public PublicPath parsePath(String rawPath, String rawQuery) {
        if (rawPath == null || rawPath.length() > MAX_PATH_LENGTH || !rawPath.startsWith("/")) {
            throw GatewayException.badRequest("Invalid request path");
        }
        List<String> segments = Arrays.asList(rawPath.substring(1).split("/", -1));
        if (segments.size() < 2) {
            throw GatewayException.notFound();
        }
        segments.forEach(RequestGuard::checkSegment);
        List<String> routeSegments = segments.subList(1, segments.size());
        return new PublicPath(segments.getFirst(), List.copyOf(routeSegments),
                "/" + String.join("/", routeSegments), rawQuery);
    }

    public byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            throw GatewayException.payloadTooLarge(maxBodyBytes);
        }
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) maxBodyBytes + 1);
            if (body.length > maxBodyBytes) {
                throw GatewayException.payloadTooLarge(maxBodyBytes);
            }
            return body;
        }
    }

    private static void checkSegment(String rawSegment) {
        if (rawSegment.isEmpty()) {
            throw GatewayException.badRequest("Empty path segment");
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(rawSegment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw GatewayException.badRequest("Invalid percent-encoding in path");
        }
        if (decoded.equals(".") || decoded.equals("..")) {
            throw GatewayException.badRequest("Dot segments are not allowed in the path");
        }
        if (decoded.chars().anyMatch(c -> c == '/' || c == '\\' || c < 0x20 || c == 0x7f)) {
            throw GatewayException.badRequest("Path contains a forbidden character");
        }
    }
}
