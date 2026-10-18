package in.code.gatekeeper.management.repository;

import in.code.gatekeeper.management.entity.Organization;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    // SELECT ... FOR UPDATE: every change inside an organization takes this lock first, so concurrent
    // changes are serialized and get consecutive configuration versions in commit order
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    Optional<Organization> findByIdForUpdate(@Param("id") Long id);

    List<Organization> findAllByActiveTrueOrderByName();

    boolean existsByName(String name);

    boolean existsBySlug(String slug);
}
