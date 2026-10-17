package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.Role;
import in.code.gatekeeper.management.enums.RoleName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);
}
