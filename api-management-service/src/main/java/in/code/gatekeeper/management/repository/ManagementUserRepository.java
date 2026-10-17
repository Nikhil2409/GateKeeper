package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.ManagementUser;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ManagementUserRepository extends JpaRepository<ManagementUser, Long> {

    // Login: role and organization are needed to build the JWT
    @EntityGraph(attributePaths = {"role", "organization"})
    Optional<ManagementUser> findByUsername(String username);

    // Every authenticated request: is the user still active and its organization not suspended?
    @EntityGraph(attributePaths = {"organization"})
    Optional<ManagementUser> findWithOrganizationById(Long id);
}
