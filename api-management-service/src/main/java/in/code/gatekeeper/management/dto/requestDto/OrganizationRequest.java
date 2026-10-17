package in.code.gatekeeper.management.dto.requestDto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * slug becomes the organization's public path prefix on the gateway (/{slug}/...), so it cannot change later.
 */
public record OrganizationRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,99}$",
                message = "must be 2-100 lowercase letters, digits or hyphens") String slug) {
}
