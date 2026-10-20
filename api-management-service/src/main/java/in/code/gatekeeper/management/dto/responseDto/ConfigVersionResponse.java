package in.code.gatekeeper.management.dto.responseDto;

import java.time.LocalDateTime;

// One kept version of an organization's configuration; current = the configuration in effect now
public record ConfigVersionResponse(long version, String changeType, String changeDetail, String changedBy,
                                    String correlationId, LocalDateTime dateCreated, long activeRoutes,
                                    boolean tokenSettingsConfigured, boolean current) {
}
