package in.code.gatekeeper.gateway.registry;

import in.code.gatekeeper.gateway.model.OrganizationConfig;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The gateway's in-memory copy of every organization's configuration, keyed by slug (the first path segment).
 *
 * Reads (every request) never lock: one map lookup returns an immutable OrgRoutes.
 * Writes (messages from gateway.org-config) replace an organization's entry only if the incoming version is
 * newer, atomically per organization. Same or older versions are ignored, which makes duplicate and out-of-order
 * updates harmless. Suspended/deleted organizations stay in the map with no routes, so their version is remembered
 * and a late older update can't bring their routes back.
 */
@Component
public class RouteRegistry {

    private final ConcurrentHashMap<String, OrgRoutes> organizations = new ConcurrentHashMap<>();
    private volatile boolean ready;
    private volatile Instant lastUpdateAt;

    // Returns true if the configuration replaced what we had, false if it was stale and ignored
    public boolean apply(OrganizationConfig config) {
        AtomicBoolean applied = new AtomicBoolean(false);
        organizations.compute(config.organizationSlug(), (slug, current) -> {
            if (current != null && config.configurationVersion() <= current.version()) {
                return current;
            }
            applied.set(true);
            return OrgRoutes.from(config);
        });
        if (applied.get()) {
            lastUpdateAt = Instant.now();
        }
        return applied.get();
    }

    // Called once the whole topic has been read up to where it ended at startup: the table is complete
    public void markReady() {
        ready = true;
    }

    public Optional<OrgRoutes> find(String slug) {
        return Optional.ofNullable(organizations.get(slug));
    }

    public Collection<OrgRoutes> all() {
        return List.copyOf(organizations.values());
    }

    // Not ready until the configuration is complete: serving with a partial table would reject valid traffic
    public boolean isReady() {
        return ready;
    }

    public Instant lastUpdateAt() {
        return lastUpdateAt;
    }
}
