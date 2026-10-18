package in.code.gatekeeper.management.dto.requestDto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Example for an Auth0 tenant: issuer=https://acme.eu.auth0.com/,
 * jwksUri=https://acme.eu.auth0.com/.well-known/jwks.json, audience=acme-api
 */
public record TokenSettingsRequest(
        @NotBlank @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL") String issuer,
        @NotBlank @Size(max = 1000) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL") String jwksUri,
        @NotBlank @Size(max = 200) String audience) {
}
