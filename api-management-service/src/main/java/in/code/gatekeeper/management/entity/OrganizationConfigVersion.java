package in.code.gatekeeper.management.entity;

import in.code.gatekeeper.management.enums.ConfigChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.Length;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

// Append-only: a row is written once and only ever deleted when it falls out of the kept history
@Entity
@Table(name = "organization_config_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(nullable = false, updatable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 40, updatable = false)
    private ConfigChangeType changeType;

    @Column(name = "change_detail", length = 200, updatable = false)
    private String changeDetail;

    @Column(name = "changed_by", length = 50, updatable = false)
    private String changedBy;

    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    @Lob
    @Column(nullable = false, length = Length.LONG32, updatable = false)
    private String snapshot;

    @CreationTimestamp
    @Column(name = "date_created", nullable = false, updatable = false)
    private LocalDateTime dateCreated;

    public OrganizationConfigVersion(Organization organization, long version, ConfigChangeType changeType,
                                     String changeDetail, String changedBy, String correlationId, String snapshot) {
        this.organization = organization;
        this.version = version;
        this.changeType = changeType;
        this.changeDetail = changeDetail;
        this.changedBy = changedBy;
        this.correlationId = correlationId;
        this.snapshot = snapshot;
    }
}
