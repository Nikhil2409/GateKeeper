package in.code.gatekeeper.analytic.dto.responseDto;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;

import java.time.Instant;
import java.util.List;

public record RequestLogPageResponse(long total, int page, int size, List<Entry> items) {

    public record Entry(String id, String correlationId, Instant timestamp, String organizationSlug, String routeId,
                        String method, String path, int status, long latencyMs, String clientIp,
                        boolean idempotentReplay) {

        public static Entry from(TrafficEventDocument event) {
            return new Entry(event.getId(), event.getCorrelationId(), event.getTimestamp(),
                    event.getOrganizationSlug(), event.getRouteId(), event.getMethod(), event.getPath(),
                    event.getStatus(), event.getLatencyMs(), event.getClientIp(),
                    "REPLAYED".equals(event.getIdempotencyOutcome()));
        }
    }
}
