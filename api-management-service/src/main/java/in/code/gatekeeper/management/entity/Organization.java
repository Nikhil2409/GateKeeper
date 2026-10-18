package in.code.gatekeeper.management.entity;

import in.code.gatekeeper.management.enums.OrganizationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Organization extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationStatus status;

    // The only configuration version: bumped on every change to this organization's routes or status
    @Column(name = "configuration_version", nullable = false)
    private long configurationVersion;

    public Organization(String name, String slug) {
        this.name = name;
        this.slug = slug;
        this.status = OrganizationStatus.ACTIVE;
    }

    public void incrementVersion() {
        configurationVersion++;
    }

    public boolean isOperational() {
        return isActive() && status == OrganizationStatus.ACTIVE;
    }
}
