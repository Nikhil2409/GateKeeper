package in.code.gatekeeper.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Trust settings the gateway uses to verify the tokens of an organization's users: only tokens signed by a key
 * from jwksUri, issued by issuer and meant for audience are accepted on that organization's routes.
 */
@Entity
@Table(name = "organization_token_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrganizationTokenSettings extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(nullable = false, length = 500)
    private String issuer;

    @Column(name = "jwks_uri", nullable = false, length = 1000)
    private String jwksUri;

    @Column(nullable = false, length = 200)
    private String audience;

    public OrganizationTokenSettings(Organization organization) {
        this.organization = organization;
    }
}
