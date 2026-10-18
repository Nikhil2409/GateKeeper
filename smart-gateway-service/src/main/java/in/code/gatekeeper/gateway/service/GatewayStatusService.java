package in.code.gatekeeper.gateway.service;

import in.code.gatekeeper.gateway.client.OrgConfigConsumer;
import in.code.gatekeeper.gateway.client.RedisStatus;
import in.code.gatekeeper.gateway.dto.HealthResponse;
import in.code.gatekeeper.gateway.dto.StatusResponse;
import in.code.gatekeeper.gateway.dto.StatusResponse.OrganizationStatus;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import in.code.gatekeeper.gateway.registry.RouteRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * What the gateway reports about itself on /_gateway/health and /_gateway/status.
 */
@Service
@RequiredArgsConstructor
public class GatewayStatusService {

    private final RouteRegistry routeRegistry;
    private final RedisStatus redisStatus;
    private final OrgConfigConsumer orgConfigConsumer;

    @Value("${gateway.instance-id}")
    private String instanceId;

    // Ready once the configuration is fully loaded from Kafka; until then the reason says what it is waiting for
    public HealthResponse health() {
        if (routeRegistry.isReady()) {
            return new HealthResponse("UP", instanceId, null, redisStatus.status());
        }
        return new HealthResponse("NOT_READY", instanceId, orgConfigConsumer.state(), redisStatus.status());
    }

    public StatusResponse status() {
        List<OrganizationStatus> organizations = routeRegistry.all().stream()
                .sorted(Comparator.comparing(OrgRoutes::slug))
                .map(OrganizationStatus::from)
                .toList();
        return new StatusResponse(instanceId, routeRegistry.isReady(), routeRegistry.lastUpdateAt(), organizations);
    }
}
