package in.code.gatekeeper.management.dto.requestDto;

import in.code.gatekeeper.management.enums.HeaderAction;
import in.code.gatekeeper.management.enums.HeaderDirection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * headerValue is required for ADD / SET and ignored for REMOVE.
 */
public record HeaderRuleRequest(
        @NotNull HeaderDirection direction,
        @NotNull HeaderAction action,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,200}$", message = "must be a valid header name") String headerName,
        @Size(max = 2000) String headerValue) {
}
