package in.code.gatekeeper.management.controller;

import in.code.gatekeeper.management.aop.RequiredRole;
import in.code.gatekeeper.management.dto.requestDto.OrganizationRequest;
import in.code.gatekeeper.management.dto.requestDto.PasswordResetRequest;
import in.code.gatekeeper.management.dto.requestDto.TenantAdminRequest;
import in.code.gatekeeper.management.dto.responseDto.OrganizationResponse;
import in.code.gatekeeper.management.dto.responseDto.TenantAdminResponse;
import in.code.gatekeeper.management.enums.OrganizationStatus;
import in.code.gatekeeper.management.enums.RoleName;
import in.code.gatekeeper.management.service.PlatformService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PLATFORM_ADMIN only.
 */
@RestController
@RequiredRole(RoleName.PLATFORM_ADMIN)
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformService platformService;

    @PostMapping("/organizations")
    public ResponseEntity<OrganizationResponse> createOrganization(@Valid @RequestBody OrganizationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(platformService.createOrganization(request));
    }

    @GetMapping("/organizations")
    public ResponseEntity<List<OrganizationResponse>> listOrganizations() {
        return ResponseEntity.ok(platformService.listOrganizations());
    }

    @GetMapping("/organizations/{id}")
    public ResponseEntity<OrganizationResponse> getOrganization(@PathVariable Long id) {
        return ResponseEntity.ok(platformService.getOrganization(id));
    }

    @PatchMapping("/organizations/{id}/suspend")
    public ResponseEntity<OrganizationResponse> suspendOrganization(@PathVariable Long id) {
        return ResponseEntity.ok(platformService.changeOrganizationStatus(id, OrganizationStatus.SUSPENDED));
    }

    @PatchMapping("/organizations/{id}/activate")
    public ResponseEntity<OrganizationResponse> activateOrganization(@PathVariable Long id) {
        return ResponseEntity.ok(platformService.changeOrganizationStatus(id, OrganizationStatus.ACTIVE));
    }

    @PostMapping("/tenant-admins")
    public ResponseEntity<TenantAdminResponse> createTenantAdmin(@Valid @RequestBody TenantAdminRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(platformService.createTenantAdmin(request));
    }

    @GetMapping("/tenant-admins")
    public ResponseEntity<List<TenantAdminResponse>> listTenantAdmins() {
        return ResponseEntity.ok(platformService.listTenantAdmins());
    }

    @GetMapping("/tenant-admins/{id}")
    public ResponseEntity<TenantAdminResponse> getTenantAdmin(@PathVariable Long id) {
        return ResponseEntity.ok(platformService.getTenantAdmin(id));
    }

    @PatchMapping("/tenant-admins/{id}/password")
    public ResponseEntity<TenantAdminResponse> resetTenantAdminPassword(@PathVariable Long id,
                                                                        @Valid @RequestBody PasswordResetRequest request) {
        return ResponseEntity.ok(platformService.resetTenantAdminPassword(id, request));
    }

    @DeleteMapping("/tenant-admins/{id}")
    public ResponseEntity<Void> deleteTenantAdmin(@PathVariable Long id) {
        platformService.deleteTenantAdmin(id);
        return ResponseEntity.noContent().build();
    }
}
