package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.enums.RoleName;

public record CurrentUserResponse(Long userId, String username, RoleName role, Long organizationId,
                                  String organizationName, String organizationSlug) {
}
