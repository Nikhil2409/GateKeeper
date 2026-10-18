package in.code.gatekeeper.gateway.registry;

import in.code.gatekeeper.gateway.model.OrganizationConfig;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.model.TokenSettings;

import java.time.Instant;
import java.util.List;

/**
 * What the gateway holds for one organization. Immutable: an update never changes an OrgRoutes, it replaces it
 * with a new one, so a request always sees one complete version (old or new), never a half-updated mix.
 * The matcher is compiled once here, so requests never parse templates.
 */
public record OrgRoutes(Long organizationId, String slug, long version, String status, TokenSettings tokenSettings,
                        List<RouteConfig> routes, RouteMatcher matcher, Instant loadedAt) {

    public static OrgRoutes from(OrganizationConfig config) {
        // Defensive: a non-active organization never serves routes, even if some were sent
        List<RouteConfig> routes = config.isActive() ? config.routes() : List.of();
        return new OrgRoutes(config.organizationId(), config.organizationSlug(), config.configurationVersion(),
                config.status(), config.tokenSettings(), routes, new RouteMatcher(routes), Instant.now());
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
