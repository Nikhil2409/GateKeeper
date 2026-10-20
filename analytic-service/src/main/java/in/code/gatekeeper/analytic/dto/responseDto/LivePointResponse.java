package in.code.gatekeeper.analytic.dto.responseDto;

import java.time.Instant;

// One step of the last 15 minutes; p95 in ms
public record LivePointResponse(Instant timestamp, double requestsPerSecond, double errorRate, long p95) {
}
