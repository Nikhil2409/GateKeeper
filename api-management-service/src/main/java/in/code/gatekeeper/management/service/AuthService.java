package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.requestDto.LoginRequest;
import in.code.gatekeeper.management.dto.responseDto.CurrentUserResponse;
import in.code.gatekeeper.management.dto.responseDto.LoginResponse;
import in.code.gatekeeper.management.entity.ManagementUser;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.enums.RoleName;
import in.code.gatekeeper.management.repository.ManagementUserRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class AuthService {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_ORGANIZATION_ID = "org_id";

    private final ManagementUserRepository managementUserRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final String issuer;
    private final Duration accessTokenTtl;

    public AuthService(ManagementUserRepository managementUserRepository,
                       OrganizationRepository organizationRepository,
                       PasswordEncoder passwordEncoder,
                       JwtEncoder jwtEncoder,
                       @Value("${gatekeeper.security.jwt.issuer}") String issuer,
                       @Value("${gatekeeper.security.jwt.access-token-ttl}") Duration accessTokenTtl) {
        this.managementUserRepository = managementUserRepository;
        this.organizationRepository = organizationRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        ManagementUser user = managementUserRepository.findByUsername(request.username())
                .filter(found -> passwordEncoder.matches(request.password(), found.getPassword()))
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        if (!user.canAuthenticate()) {
            throw new DisabledException("Account is inactive or organization is suspended");
        }

        RoleName role = user.getRole().getName();
        Long organizationId = user.getOrganization() == null ? null : user.getOrganization().getId();
        Instant issuedAt = Instant.now();

        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(accessTokenTtl))
                .claim(CLAIM_USER_ID, user.getId())
                .claim(CLAIM_ROLE, role.name());
        if (organizationId != null) {
            claims.claim(CLAIM_ORGANIZATION_ID, organizationId);
        }

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new LoginResponse(token, "Bearer", accessTokenTtl.toSeconds(), role, organizationId);
    }

    // Checked on every authenticated request by the JWT decoder (see SecurityConfig)
    @Transactional(readOnly = true)
    public boolean isAccountActive(Long userId) {
        return managementUserRepository.findWithOrganizationById(userId)
                .map(ManagementUser::canAuthenticate)
                .orElse(false);
    }

    // The only source of a tenant admin's organization: the signed token, never the request
    public static Long organizationIdOf(Jwt jwt) {
        Number organizationId = jwt.getClaim(CLAIM_ORGANIZATION_ID);
        if (organizationId == null) {
            throw new AccessDeniedException("Token is not bound to an organization");
        }
        return organizationId.longValue();
    }

    @Transactional(readOnly = true)
    public CurrentUserResponse currentUser(Jwt jwt) {
        Number userId = jwt.getClaim(CLAIM_USER_ID);
        Number organizationId = jwt.getClaim(CLAIM_ORGANIZATION_ID);
        Organization organization = organizationId == null ? null
                : organizationRepository.findById(organizationId.longValue()).orElse(null);
        return new CurrentUserResponse(
                userId.longValue(),
                jwt.getSubject(),
                RoleName.valueOf(jwt.getClaimAsString(CLAIM_ROLE)),
                organization == null ? null : organization.getId(),
                organization == null ? null : organization.getName(),
                organization == null ? null : organization.getSlug());
    }
}
