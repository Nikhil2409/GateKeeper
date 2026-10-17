package in.code.gatekeeper.management.entity;

import in.code.gatekeeper.management.enums.HeaderAction;
import in.code.gatekeeper.management.enums.HeaderDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
@Table(name = "route_header_rules")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RouteHeaderRule extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_pk", nullable = false)
    private ApiRoute route;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HeaderDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HeaderAction action;

    @Column(name = "header_name", nullable = false, length = 200)
    private String headerName;

    @Column(name = "header_value", length = 2000)
    private String headerValue;

    public RouteHeaderRule(HeaderDirection direction, HeaderAction action, String headerName, String headerValue) {
        this.direction = direction;
        this.action = action;
        this.headerName = headerName;
        this.headerValue = headerValue;
    }
}
