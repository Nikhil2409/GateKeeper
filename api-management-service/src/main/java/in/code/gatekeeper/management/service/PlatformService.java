package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.requestDto.OrganizationRequest;
import in.code.gatekeeper.management.dto.requestDto.PasswordResetRequest;
import in.code.gatekeeper.management.dto.requestDto.TenantAdminRequest;
import in.code.gatekeeper.management.dto.responseDto.OrganizationResponse;
import in.code.gatekeeper.management.dto.responseDto.TenantAdminResponse;
import in.code.gatekeeper.management.entity.ManagementUser;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.Role;
import in.code.gatekeeper.management.enums.OrganizationStatus;
import in.code.gatekeeper.management.enums.RoleName;
import in.code.gatekeeper.management.exception.ApiException;
import in.code.gatekeeper.management.repository.ManagementUserRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import in.code.gatekeeper.management.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PlatformService {

    private final OrganizationRepository organizationRepository;
    private final ManagementUserRepository managementUserRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    // ---------- organizations ----------

    public OrganizationResponse createOrganization(OrganizationRequest request) {
        String name = request.name().trim();
        if (organizationRepository.existsByName(name)) {
            throw ApiException.conflict("Organization name '" + name + "' is already taken");
        }
        if (organizationRepository.existsBySlug(request.slug())) {
            throw ApiException.conflict("Organization slug '" + request.slug() + "' is already taken");
        }
        return OrganizationResponse.from(organizationRepository.save(new Organization(name, request.slug())));
    }

    @Transactional(readOnly = true)
    public List<OrganizationResponse> listOrganizations() {
        return organizationRepository.findAllByActiveTrueOrderByName().stream()
                .map(OrganizationResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrganizationResponse getOrganization(Long id) {
        return OrganizationResponse.from(findOrganization(id));
    }

    // Suspending blocks the tenant admin immediately and takes the org's routes off the gateway,
    // so it is a configuration change and bumps the organization's version
    public OrganizationResponse changeOrganizationStatus(Long id, OrganizationStatus status) {
        Organization organization = organizationRepository.findByIdForUpdate(id)
                .filter(Organization::isActive)
                .orElseThrow(() -> ApiException.notFound("Organization " + id + " not found"));
        if (organization.getStatus() != status) {
            organization.setStatus(status);
            bumpVersion(organization);
        }
        return OrganizationResponse.from(organizationRepository.saveAndFlush(organization));
    }

    // ---------- tenant admins ----------

    public TenantAdminResponse createTenantAdmin(TenantAdminRequest request) {
        Organization organization = findOrganization(request.organizationId());
        if (managementUserRepository.existsByOrganizationIdAndActiveTrue(organization.getId())) {
            throw ApiException.conflict("Organization '" + organization.getName() + "' already has a tenant admin");
        }
        if (managementUserRepository.existsByUsername(request.username())) {
            throw ApiException.conflict("Username '" + request.username() + "' is already taken");
        }

        Role role = roleRepository.findByName(RoleName.TENANT_ADMIN)
                .orElseThrow(() -> new IllegalStateException("Role TENANT_ADMIN is not seeded"));
        ManagementUser user = new ManagementUser(request.username(), passwordEncoder.encode(request.password()),
                role, organization);
        return TenantAdminResponse.from(managementUserRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<TenantAdminResponse> listTenantAdmins() {
        return managementUserRepository.findAllByRole_NameAndActiveTrueOrderByUsername(RoleName.TENANT_ADMIN).stream()
                .map(TenantAdminResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TenantAdminResponse getTenantAdmin(Long id) {
        return TenantAdminResponse.from(findTenantAdmin(id));
    }

    public TenantAdminResponse resetTenantAdminPassword(Long id, PasswordResetRequest request) {
        ManagementUser user = findTenantAdmin(id);
        user.setPassword(passwordEncoder.encode(request.password()));
        return TenantAdminResponse.from(managementUserRepository.saveAndFlush(user));
    }

    // Soft delete: existing tokens stop working on the next request and the org can get a new admin
    public void deleteTenantAdmin(Long id) {
        findTenantAdmin(id).setActive(false);
    }

    // Every configuration change inside an organization bumps its single version
    private void bumpVersion(Organization organization) {
        organization.incrementVersion();
    }

    private Organization findOrganization(Long id) {
        return organizationRepository.findById(id)
                .filter(Organization::isActive)
                .orElseThrow(() -> ApiException.notFound("Organization " + id + " not found"));
    }

    private ManagementUser findTenantAdmin(Long id) {
        return managementUserRepository.findByIdAndRole_NameAndActiveTrue(id, RoleName.TENANT_ADMIN)
                .orElseThrow(() -> ApiException.notFound("Tenant admin " + id + " not found"));
    }
}
