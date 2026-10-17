package in.code.gatekeeper.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Base class for mutable tables. Rows are never hard deleted: active = false is a soft delete
 * and dateUpdated of an inactive row is the time it was deleted.
 */
@MappedSuperclass
@Getter
public abstract class AuditableEntity {

    @Setter
    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "date_created", nullable = false, updatable = false)
    private LocalDateTime dateCreated;

    @UpdateTimestamp
    @Column(name = "date_updated", nullable = false)
    private LocalDateTime dateUpdated;
}
