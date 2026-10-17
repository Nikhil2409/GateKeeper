package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.enums.RoleName;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, RoleName role,
                            Long organizationId) {
}
