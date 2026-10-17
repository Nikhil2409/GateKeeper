package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.enums.OrganizationStatus;

import java.time.LocalDateTime;

public record OrganizationResponse(Long id, String name, String slug, OrganizationStatus status,
                                   LocalDateTime dateCreated, LocalDateTime dateUpdated) {

    public static OrganizationResponse from(Organization organization) {
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
                organization.getStatus(), organization.getDateCreated(), organization.getDateUpdated());
    }
}
