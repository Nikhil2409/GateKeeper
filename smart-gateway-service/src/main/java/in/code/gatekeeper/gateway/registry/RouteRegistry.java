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
 * Writes (snapshot, resync, later Kafka events) replace an organization's entry only if the incoming version is
 * newer, atomically per organization. Same or older versions are ignored, which makes duplicate and out-of-order
 * updates harmless. Suspended/deleted organizations stay in the map with no routes, so their version is remembered
 * and a late older update can't bring their routes back.
 */
@Component
public class RouteRegistry {

    private final ConcurrentHashMap<String, OrgRoutes> organizations = new ConcurrentHashMap<>();
    private volatile boolean ready;
    private volatile Instant lastSnapshotAt;

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
        return applied.get();
    }

    // A full snapshot from api-management: every organization goes through the same version rule.
    // Returns how many organizations changed.
    public int applySnapshot(List<OrganizationConfig> snapshot) {
        int changed = (int) snapshot.stream().filter(this::apply).count();
        lastSnapshotAt = Instant.now();
        ready = true;
        return changed;
    }

    public Optional<OrgRoutes> find(String slug) {
        return Optional.ofNullable(organizations.get(slug));
    }

    public Collection<OrgRoutes> all() {
        return List.copyOf(organizations.values());
    }

    // Not ready until the first snapshot loaded: serving with an empty or partial table would reject valid traffic
    public boolean isReady() {
        return ready;
    }

    public Instant lastSnapshotAt() {
        return lastSnapshotAt;
    }
}
