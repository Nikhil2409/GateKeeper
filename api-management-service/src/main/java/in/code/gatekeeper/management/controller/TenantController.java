package in.code.gatekeeper.management.controller;

import in.code.gatekeeper.management.aop.RequiredRole;
import in.code.gatekeeper.management.dto.requestDto.RouteRequest;
import in.code.gatekeeper.management.dto.responseDto.RouteResponse;
import in.code.gatekeeper.management.enums.RoleName;
import in.code.gatekeeper.management.service.AuthService;
import in.code.gatekeeper.management.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TENANT_ADMIN only. The organization always comes from the JWT.
 */
@RestController
@RequiredRole(RoleName.TENANT_ADMIN)
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/routes")
    public ResponseEntity<List<RouteResponse>> listRoutes(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(tenantService.listRoutes(AuthService.organizationIdOf(jwt)));
    }

    @GetMapping("/routes/{routeId}")
    public ResponseEntity<RouteResponse> getRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable String routeId) {
        return ResponseEntity.ok(tenantService.getRoute(AuthService.organizationIdOf(jwt), routeId));
    }

    @PostMapping("/routes")
    public ResponseEntity<RouteResponse> createRoute(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody RouteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(tenantService.createRoute(AuthService.organizationIdOf(jwt), request));
    }

    @PutMapping("/routes/{routeId}")
    public ResponseEntity<RouteResponse> updateRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable String routeId,
                                                     @Valid @RequestBody RouteRequest request) {
        return ResponseEntity.ok(tenantService.updateRoute(AuthService.organizationIdOf(jwt), routeId, request));
    }

    @DeleteMapping("/routes/{routeId}")
    public ResponseEntity<Void> deleteRoute(@AuthenticationPrincipal Jwt jwt, @PathVariable String routeId) {
        tenantService.setRouteActive(AuthService.organizationIdOf(jwt), routeId, false);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/routes/{routeId}/activate")
    public ResponseEntity<RouteResponse> activateRoute(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable String routeId) {
        return ResponseEntity.ok(tenantService.setRouteActive(AuthService.organizationIdOf(jwt), routeId, true));
    }
}
