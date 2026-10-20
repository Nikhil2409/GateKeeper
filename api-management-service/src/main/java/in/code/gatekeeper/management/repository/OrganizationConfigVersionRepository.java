package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.OrganizationConfigVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationConfigVersionRepository extends JpaRepository<OrganizationConfigVersion, Long> {

    List<OrganizationConfigVersion> findAllByOrganizationIdOrderByVersionDesc(Long organizationId);

    Optional<OrganizationConfigVersion> findByOrganizationIdAndVersion(Long organizationId, long version);
}
