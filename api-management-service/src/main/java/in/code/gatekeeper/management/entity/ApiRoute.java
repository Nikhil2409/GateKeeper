package in.code.gatekeeper.management.entity;

import in.code.gatekeeper.management.enums.HttpMethodType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "api_routes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiRoute extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false, updatable = false)
    private Organization organization;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "route_id", nullable = false, length = 100)
    private String routeId;

    @Column(name = "path_pattern", nullable = false, length = 500)
    private String pathPattern;

    @Column(name = "target_base_url", nullable = false, length = 1000)
    private String targetBaseUrl;

    @Column(name = "target_path_prefix", nullable = false, length = 500)
    private String targetPathPrefix;

    @Column(name = "requests_per_minute")
    private Integer requestsPerMinute;

    @Column(name = "response_timeout_ms", nullable = false)
    private int responseTimeoutMs;

    @Column(name = "idempotency_enabled", nullable = false)
    private boolean idempotencyEnabled;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "route_allowed_methods", joinColumns = @JoinColumn(name = "route_pk"))
    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", nullable = false, length = 10)
    private Set<HttpMethodType> allowedMethods = new HashSet<>();

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<RouteHeaderRule> headerRules = new LinkedHashSet<>();

    public ApiRoute(Organization organization, String routeId) {
        this.organization = organization;
        this.routeId = routeId;
    }

    public void addHeaderRule(RouteHeaderRule rule) {
        rule.setRoute(this);
        headerRules.add(rule);
    }
    public void clearHeaderRules() {
        headerRules.clear();
    }
}
