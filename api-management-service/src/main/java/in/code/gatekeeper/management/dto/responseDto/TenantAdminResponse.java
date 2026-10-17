package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.ManagementUser;

import java.time.LocalDateTime;

public record TenantAdminResponse(Long id, String username, Long organizationId, String organizationName,
                                  LocalDateTime dateCreated, LocalDateTime dateUpdated) {

    public static TenantAdminResponse from(ManagementUser user) {
        return new TenantAdminResponse(user.getId(), user.getUsername(), user.getOrganization().getId(),
                user.getOrganization().getName(), user.getDateCreated(), user.getDateUpdated());
    }
}
