package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
