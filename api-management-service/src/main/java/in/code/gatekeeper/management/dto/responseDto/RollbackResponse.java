package in.code.gatekeeper.management.dto.responseDto;

// restoredVersion: the version whose configuration is back; newVersion: the version the rollback itself created
public record RollbackResponse(long restoredVersion, long newVersion, long activeRoutes,
                               boolean tokenSettingsConfigured) {
}
