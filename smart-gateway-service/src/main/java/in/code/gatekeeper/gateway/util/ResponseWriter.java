package in.code.gatekeeper.gateway.util;

import in.code.gatekeeper.gateway.check.HeaderFilter;
import in.code.gatekeeper.gateway.check.IdempotencyCheck;
import in.code.gatekeeper.gateway.check.IdempotencyCheck.StoredResponse;
import in.code.gatekeeper.gateway.check.IdempotencyCheck.Ticket;
import in.code.gatekeeper.gateway.model.RouteConfig;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Writes what goes back to the caller: the upstream's response (status, filtered headers, body streamed through)
 * or a stored idempotent response being replayed.
 */
@Component
@RequiredArgsConstructor
public class ResponseWriter {

    private final HeaderFilter headerFilter;
    private final IdempotencyCheck idempotencyCheck;

    /**
     * ticket: the idempotency ticket of this request, or null when the check did not apply. With a ticket the body is
     * buffered up to the limit so it can be stored for replays; 5xx and oversized responses are not stored (the key
     * is released, so a retry runs again).
     */
    public void writeUpstream(HttpServletResponse response, HttpResponse<InputStream> upstream, RouteConfig route,
                              Ticket ticket) throws IOException {
        Map<String, List<String>> headers = headerFilter.responseHeaders(upstream.headers(), route);
        response.setStatus(upstream.statusCode());
        addHeaders(response, headers);

        try (InputStream in = upstream.body(); OutputStream out = response.getOutputStream()) {
            if (ticket == null) {
                in.transferTo(out);
                return;
            }
            int limit = idempotencyCheck.getMaxStoredResponseBytes();
            byte[] start = in.readNBytes(limit + 1);
            if (start.length <= limit && upstream.statusCode() < 500) {
                idempotencyCheck.complete(ticket, upstream.statusCode(), headers, start);
            } else {
                idempotencyCheck.release(ticket);
            }
            out.write(start);
            in.transferTo(out);
        }
    }

    public void writeReplay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        addHeaders(response, stored.headers());
        response.setHeader(IdempotencyCheck.REPLAYED_HEADER, "true");
        try (OutputStream out = response.getOutputStream()) {
            out.write(stored.body() == null ? new byte[0] : stored.body());
        }
    }

    private static void addHeaders(HttpServletResponse response, Map<String, List<String>> headers) {
        headers.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }
}
