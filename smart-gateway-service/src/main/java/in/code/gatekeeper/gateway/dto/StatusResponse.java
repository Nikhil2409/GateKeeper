package in.code.gatekeeper.gateway.dto;

import in.code.gatekeeper.gateway.registry.OrgRoutes;

import java.time.Instant;
import java.util.List;

// Operational data only: versions and counts, never route targets or tenant data
public record StatusResponse(String instanceId, boolean ready, Instant lastUpdateAt,
                             List<OrganizationStatus> organizations) {

    // openCircuits: route ids whose circuit breaker is open or probing on this instance
    public record OrganizationStatus(String slug, long version, String status, int routes,
                                     boolean tokenSettingsConfigured, Instant loadedAt, List<String> openCircuits) {

        public static OrganizationStatus from(OrgRoutes organization, List<String> openCircuits) {
            return new OrganizationStatus(organization.slug(), organization.version(), organization.status(),
                    organization.routes().size(), organization.tokenSettings() != null, organization.loadedAt(),
                    openCircuits);
        }
    }
}
