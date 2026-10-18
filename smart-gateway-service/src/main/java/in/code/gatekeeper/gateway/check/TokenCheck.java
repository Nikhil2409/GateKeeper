package in.code.gatekeeper.gateway.check;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.DefaultJOSEObjectTypeVerifier;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import in.code.gatekeeper.gateway.client.JwksRetriever;
import in.code.gatekeeper.gateway.exception.GatewayException;
import in.code.gatekeeper.gateway.model.TokenSettings;
import in.code.gatekeeper.gateway.registry.OrgRoutes;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.text.ParseException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every tenant route requires a user token issued by the organization's own auth server. The token must be signed
 * by a key from the organization's JWKS, carry exactly the configured issuer, include the configured audience,
 * not be expired (60 s clock skew) and have a subject. The subject becomes the user id for quota and idempotency.
 *
 * One verifier per organization, rebuilt when its token settings change. Keys are cached (Nimbus default: 5 min)
 * and refetched when a token names an unknown key id, so key rotation at the tenant just works.
 * An organization without token settings can't be called at all (401).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCheck {

    // Asymmetric only: the gateway must never hold a secret that could also mint tokens
    private static final Set<JWSAlgorithm> ALLOWED_ALGORITHMS = Set.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384,
            JWSAlgorithm.RS512, JWSAlgorithm.PS256, JWSAlgorithm.ES256, JWSAlgorithm.ES384);

    private final JwksRetriever jwksRetriever;

    private final Map<Long, OrgVerifier> verifiers = new ConcurrentHashMap<>();

    private record OrgVerifier(TokenSettings settings, ConfigurableJWTProcessor<SecurityContext> processor) {
    }

    // Returns the verified user id (the token's subject)
    public String check(OrgRoutes organization, HttpServletRequest request) {
        TokenSettings settings = organization.tokenSettings();
        if (settings == null) {
            throw GatewayException.unauthorized("This organization does not accept tokens yet");
        }
        String token = bearerToken(request);

        try {
            JWTClaimsSet claims = verifierFor(organization.organizationId(), settings).process(token, null);
            return claims.getSubject();
        } catch (KeySourceException e) {
            // the organization's JWKS could not be fetched: not the caller's fault, and never let it through
            log.warn("JWKS of organization {} unavailable: {}", organization.slug(), e.getMessage());
            throw GatewayException.serviceUnavailable("Token keys of this organization are unavailable, retry later");
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.debug("Rejected token for organization {}: {}", organization.slug(), e.getMessage());
            throw GatewayException.unauthorized("Invalid or expired token");
        }
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() == 7) {
            throw GatewayException.unauthorized("Bearer token required");
        }
        return header.substring(7).trim();
    }

    private ConfigurableJWTProcessor<SecurityContext> verifierFor(Long organizationId, TokenSettings settings) {
        OrgVerifier current = verifiers.get(organizationId);
        if (current == null || !current.settings().equals(settings)) {
            current = new OrgVerifier(settings, buildProcessor(settings));
            verifiers.put(organizationId, current);
        }
        return current.processor();
    }

    private ConfigurableJWTProcessor<SecurityContext> buildProcessor(TokenSettings settings) {
        JWKSource<SecurityContext> keys;
        try {
            keys = JWKSourceBuilder.create(URI.create(settings.jwksUri()).toURL(), jwksRetriever).build();
        } catch (MalformedURLException | IllegalArgumentException e) {
            log.warn("Invalid JWKS URL {}", settings.jwksUri());
            throw GatewayException.serviceUnavailable("Token keys of this organization are unavailable, retry later");
        }

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        // "JWT" or the access-token type "at+jwt" (RFC 9068), or no type at all
        processor.setJWSTypeVerifier(new DefaultJOSEObjectTypeVerifier<>(
                JOSEObjectType.JWT, new JOSEObjectType("at+jwt"), null));
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALLOWED_ALGORITHMS, keys));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(settings.audience(),
                new JWTClaimsSet.Builder().issuer(settings.issuer()).build(), Set.of("sub", "exp")));
        return processor;
    }
}
