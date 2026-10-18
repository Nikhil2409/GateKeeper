package in.code.gatekeeper.management.dto.responseDto;

import java.time.LocalDateTime;

public record HealthResponse(String serviceStatus, String dbStatus, String kafkaStatus, LocalDateTime timeStatus) {
}
