package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.ApiRoute;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Tenant queries always filter by organizationId, so one tenant can never load another tenant's route.
 */
public interface ApiRouteRepository extends JpaRepository<ApiRoute, Long> {

    @EntityGraph(attributePaths = {"allowedMethods", "headerRules"})
    List<ApiRoute> findAllByOrganizationIdOrderByRouteId(Long organizationId);

    @EntityGraph(attributePaths = {"allowedMethods", "headerRules"})
    Optional<ApiRoute> findByOrganizationIdAndRouteId(Long organizationId, String routeId);

    boolean existsByOrganizationIdAndRouteId(Long organizationId, String routeId);

    boolean existsByOrganizationIdAndPathPattern(Long organizationId, String pathPattern);

    // Gateway snapshot: every active route of every active organization, children loaded in one query
    @Query("""
            select distinct r from ApiRoute r
            join fetch r.organization o
            left join fetch r.allowedMethods
            left join fetch r.headerRules
            where r.active = true
              and o.status = in.code.gatekeeper.management.enums.OrganizationStatus.ACTIVE
              and o.active = true
            """)
    List<ApiRoute> findAllForGatewaySnapshot();
}
