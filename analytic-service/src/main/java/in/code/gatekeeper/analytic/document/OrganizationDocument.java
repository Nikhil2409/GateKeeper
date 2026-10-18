package in.code.gatekeeper.analytic.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * The latest configuration of one organization, as published by api-management on gateway.org-config: its name for
 * display and its route limits for the traffic-policy views. Replaced only by a newer configurationVersion,
 * the same rule the gateway uses.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Document("organizations")
public class OrganizationDocument {

    @Id
    private Long id;

    private String slug;
    private String name;
    private String status;
    private long configurationVersion;
    private List<RouteLimit> routes;
    private Instant updatedAt;

    public record RouteLimit(String routeId, Integer requestsPerMinute, Integer perUserRequestsPerMinute,
                             boolean idempotencyEnabled) {
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
