package in.code.gatekeeper.management.controller;

import in.code.gatekeeper.management.aop.RequiredRole;
import in.code.gatekeeper.management.dto.responseDto.GatewayRouteResponse;
import in.code.gatekeeper.management.enums.RoleName;
import in.code.gatekeeper.management.service.GatewayService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GATEWAY_SERVICE only. Read-only.
 */
@RestController
@RequiredRole(RoleName.GATEWAY_SERVICE)
@RequestMapping("/internal/gateway")
@RequiredArgsConstructor
public class GatewayController {

    private final GatewayService gatewayService;

    @GetMapping("/routes")
    public ResponseEntity<List<GatewayRouteResponse>> getRoutes() {
        return ResponseEntity.ok(gatewayService.getRouteSnapshot());
    }
}
