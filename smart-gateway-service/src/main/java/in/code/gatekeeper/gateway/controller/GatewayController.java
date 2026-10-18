package in.code.gatekeeper.gateway.controller;

import in.code.gatekeeper.gateway.dto.HealthResponse;
import in.code.gatekeeper.gateway.dto.StatusResponse;
import in.code.gatekeeper.gateway.service.GatewayStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway's own endpoints. "_" can never start an organization slug, so /_gateway/** never collides with
 * tenant traffic (/{slug}/...). Public, and they only expose operational data: no route targets, no tenant data.
 */
@RestController
@RequestMapping("/_gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayStatusService gatewayStatusService;

    // For load balancers: 503 until the configuration is loaded, so no traffic reaches a gateway that cannot route it
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        HealthResponse health = gatewayStatusService.health();
        return ResponseEntity.status(health.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(health);
    }

    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status() {
        return ResponseEntity.ok(gatewayStatusService.status());
    }
}
