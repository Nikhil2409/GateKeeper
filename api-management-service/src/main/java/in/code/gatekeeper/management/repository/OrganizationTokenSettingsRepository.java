package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.OrganizationTokenSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrganizationTokenSettingsRepository extends JpaRepository<OrganizationTokenSettings, Long> {

    Optional<OrganizationTokenSettings> findByOrganizationId(Long organizationId);

    // Gateway snapshot: settings of every organization in one query
    List<OrganizationTokenSettings> findAllByActiveTrue();
}
