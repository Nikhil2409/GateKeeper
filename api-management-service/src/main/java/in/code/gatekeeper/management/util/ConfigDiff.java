package in.code.gatekeeper.management.util;

import in.code.gatekeeper.management.dto.ConfigSnapshot;
import in.code.gatekeeper.management.dto.ConfigSnapshot.RouteSnapshot;
import in.code.gatekeeper.management.dto.requestDto.RouteRequest;
import in.code.gatekeeper.management.dto.requestDto.TokenSettingsRequest;
import in.code.gatekeeper.management.dto.responseDto.ConfigPreviewResponse.Changes;
import in.code.gatekeeper.management.dto.responseDto.ConfigPreviewResponse.FieldChange;
import in.code.gatekeeper.management.dto.responseDto.ConfigPreviewResponse.RouteChange;
import in.code.gatekeeper.management.dto.responseDto.ConfigPreviewResponse.RouteSummary;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

// What changes if the current configuration is replaced by a stored one (only active routes matter to the gateway)
public final class ConfigDiff {

    private ConfigDiff() {
    }

    public static Changes between(ConfigSnapshot current, ConfigSnapshot target) {
        Map<String, RouteRequest> now = active(current);
        Map<String, RouteRequest> then = active(target);

        List<RouteSummary> switchedOn = then.values().stream()
                .filter(route -> !now.containsKey(route.routeId()))
                .map(ConfigDiff::summary).toList();
        List<RouteSummary> switchedOff = now.values().stream()
                .filter(route -> !then.containsKey(route.routeId()))
                .map(ConfigDiff::summary).toList();

        List<RouteChange> changed = new ArrayList<>();
        int unchanged = 0;
        for (RouteRequest after : then.values()) {
            RouteRequest before = now.get(after.routeId());
            if (before == null) {
                continue;
            }
            List<FieldChange> fields = routeChanges(before, after);
            if (fields.isEmpty()) {
                unchanged++;
            } else {
                changed.add(new RouteChange(after.routeId(), after.pathPattern(), fields));
            }
        }
        return new Changes(switchedOn, switchedOff, changed,
                tokenChanges(current.tokenSettings(), target.tokenSettings()), unchanged);
    }

    private static Map<String, RouteRequest> active(ConfigSnapshot snapshot) {
        return snapshot.routes().stream()
                .filter(RouteSnapshot::active)
                .map(RouteSnapshot::route)
                .collect(Collectors.toMap(RouteRequest::routeId, Function.identity(), (a, b) -> a,
                        TreeMap::new));
    }

    private static RouteSummary summary(RouteRequest route) {
        return new RouteSummary(route.routeId(), sorted(route.allowedMethods()), route.pathPattern(),
                route.targetBaseUrl() + nullToEmpty(route.targetPathPrefix()));
    }

    private static List<FieldChange> routeChanges(RouteRequest before, RouteRequest after) {
        List<FieldChange> changes = new ArrayList<>();
        compare(changes, "name", before.name(), after.name());
        compare(changes, "path", before.pathPattern(), after.pathPattern());
        compare(changes, "methods", sorted(before.allowedMethods()), sorted(after.allowedMethods()));
        compare(changes, "target", before.targetBaseUrl() + nullToEmpty(before.targetPathPrefix()),
                after.targetBaseUrl() + nullToEmpty(after.targetPathPrefix()));
        compare(changes, "rate limit", perMinute(before.requestsPerMinute()), perMinute(after.requestsPerMinute()));
        compare(changes, "timeout", before.responseTimeoutMs() + " ms", after.responseTimeoutMs() + " ms");
        compare(changes, "idempotency", onOff(before.idempotencyEnabled()), onOff(after.idempotencyEnabled()));
        compare(changes, "allowed headers", sortedOrNone(before.allowedHeaders()), sortedOrNone(after.allowedHeaders()));
        compare(changes, "header rules", rules(before), rules(after));
        return changes;
    }

    private static List<FieldChange> tokenChanges(TokenSettingsRequest before, TokenSettingsRequest after) {
        List<FieldChange> changes = new ArrayList<>();
        if (before == null || after == null) {
            compare(changes, "token settings", before == null ? "not configured" : "configured",
                    after == null ? "not configured" : "configured");
            if (before == null && after == null) {
                return changes;
            }
        }
        compare(changes, "issuer", before == null ? null : before.issuer(), after == null ? null : after.issuer());
        compare(changes, "JWKS URL", before == null ? null : before.jwksUri(), after == null ? null : after.jwksUri());
        compare(changes, "audience", before == null ? null : before.audience(), after == null ? null : after.audience());
        return changes;
    }

    private static void compare(List<FieldChange> changes, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, before, after));
        }
    }

    private static String rules(RouteRequest route) {
        if (route.headerRules() == null || route.headerRules().isEmpty()) {
            return "none";
        }
        return route.headerRules().stream()
                .map(rule -> rule.direction() + " " + rule.action() + " " + rule.headerName()
                        + (rule.headerValue() == null ? "" : ": " + rule.headerValue()))
                .sorted()
                .collect(Collectors.joining("; "));
    }

    private static String sorted(Collection<?> values) {
        return values == null ? "" : values.stream().map(String::valueOf).sorted().collect(Collectors.joining(", "));
    }

    private static String sortedOrNone(Collection<?> values) {
        return values == null || values.isEmpty() ? "none" : sorted(values);
    }

    private static String perMinute(Integer value) {
        return value == null ? "unlimited" : value + "/min";
    }

    private static String onOff(Boolean value) {
        return Boolean.TRUE.equals(value) ? "on" : "off";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
