package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.dto.ConfigSnapshot;

import java.util.List;

// A kept version in full, and what rolling back to it would change compared with the configuration in effect now
public record ConfigPreviewResponse(long version, boolean current, ConfigSnapshot configuration, Changes changes) {

    public record Changes(List<RouteSummary> routesSwitchedOn, List<RouteSummary> routesSwitchedOff,
                          List<RouteChange> routesChanged, List<FieldChange> tokenSettings, int routesUnchanged) {
    }

    public record RouteSummary(String routeId, String methods, String pathPattern, String target) {
    }

    public record RouteChange(String routeId, String pathPattern, List<FieldChange> changes) {
    }

    public record FieldChange(String field, String before, String after) {
    }
}
