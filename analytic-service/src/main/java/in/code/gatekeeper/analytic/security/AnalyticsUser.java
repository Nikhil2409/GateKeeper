package in.code.gatekeeper.analytic.security;

import in.code.gatekeeper.analytic.exception.ApiException;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The signed-in user as far as analytics cares: role and organization from the api-management token.
 */
public record AnalyticsUser(String role, Long organizationId) {

    public static AnalyticsUser from(Jwt jwt) {
        Number organizationId = jwt.getClaim("org_id");
        return new AnalyticsUser(jwt.getClaimAsString("role"),
                organizationId == null ? null : organizationId.longValue());
    }

    public boolean isPlatformAdmin() {
        return "PLATFORM_ADMIN".equals(role);
    }

    /**
     * Which organization a query may cover. Platform admins: the one they asked for, or null = all organizations.
     * Tenant admins: always their own, whatever they asked for, so they can never see another tenant's traffic.
     */
    public Long scope(Long requestedOrganizationId) {
        if (isPlatformAdmin()) {
            return requestedOrganizationId;
        }
        if (organizationId == null) {
            throw ApiException.forbidden("Token has no organization");
        }
        return organizationId;
    }
}
