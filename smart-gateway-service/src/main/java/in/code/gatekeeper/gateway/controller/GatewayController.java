package in.code.gatekeeper.gateway.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import in.code.gatekeeper.gateway.client.OrgConfigConsumer;
import in.code.gatekeeper.gateway.client.RedisStatus;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.registry.RouteRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * The gateway's own endpoints. "_" can never start an organization slug, so /_gateway/** never collides with
 * tenant traffic (/{slug}/...). Public, and they only expose operational data: no route targets, no tenant data.
 */
@RestController
@RequestMapping("/_gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final RouteRegistry routeRegistry;
    private final RedisStatus redisStatus;
    private final OrgConfigConsumer orgConfigConsumer;

    @Value("${gateway.instance-id}")
    private String instanceId;

    // For load balancers: 503 until the configuration is loaded, so no traffic reaches a gateway that cannot route it.
    // redis is reported alongside (UP/DOWN) but does not change readiness: without it the gateway still serves.
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        if (routeRegistry.isReady()) {
            return ResponseEntity.ok(new HealthResponse("UP", instanceId, null, redisStatus.status()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("NOT_READY", instanceId, orgConfigConsumer.state(),
                        redisStatus.status()));
    }

    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status() {
        List<OrganizationStatus> organizations = routeRegistry.all().stream()
                .sorted(Comparator.comparing(OrgRoutes::slug))
                .map(org -> new OrganizationStatus(org.slug(), org.version(), org.status(), org.routes().size(),
                        org.tokenSettings() != null, org.loadedAt()))
                .toList();
        return ResponseEntity.ok(new StatusResponse(instanceId, routeRegistry.isReady(),
                routeRegistry.lastUpdateAt(), organizations));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HealthResponse(String status, String instanceId, String reason, String redis) {
    }

    public record StatusResponse(String instanceId, boolean ready, Instant lastUpdateAt,
                                 List<OrganizationStatus> organizations) {
    }

    public record OrganizationStatus(String slug, long version, String status, int routes,
                                     boolean tokenSettingsConfigured, Instant loadedAt) {
    }
}
