package in.code.gatekeeper.management.dto.responseDto;

import in.code.gatekeeper.management.entity.OrganizationTokenSettings;

public record TokenSettingsResponse(String issuer, String jwksUri, String audience) {

    public static TokenSettingsResponse from(OrganizationTokenSettings settings) {
        return settings == null ? null
                : new TokenSettingsResponse(settings.getIssuer(), settings.getJwksUri(), settings.getAudience());
    }
}
