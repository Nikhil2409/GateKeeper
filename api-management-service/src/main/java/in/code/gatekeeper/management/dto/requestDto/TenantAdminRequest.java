package in.code.gatekeeper.management.dto.requestDto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TenantAdminRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{3,50}$",
                message = "must be 3-50 letters, digits, dots, underscores or hyphens") String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull Long organizationId) {
}
