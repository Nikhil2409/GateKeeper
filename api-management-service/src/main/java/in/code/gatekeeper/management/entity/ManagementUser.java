package in.code.gatekeeper.management.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Platform admins have no organization.
 * An organization has at most one active tenant admin (enforced by a functional unique key).
 */
@Entity
@Table(name = "management_users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ManagementUser extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(nullable = false, length = 50)
    private String username;

    // BCrypt hash, never the raw password
    @Column(nullable = false)
    private String password;

    public ManagementUser(String username, String password, Role role, Organization organization) {
        this.username = username;
        this.password = password;
        this.role = role;
        this.organization = organization;
    }

    public boolean canAuthenticate() {
        return isActive() && (organization == null || organization.isOperational());
    }
}
