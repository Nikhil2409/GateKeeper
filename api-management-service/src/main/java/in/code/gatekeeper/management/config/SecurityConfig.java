package in.code.gatekeeper.management.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import in.code.gatekeeper.management.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Authentication only: every request except the public ones must carry "Authorization: Bearer <jwt>".
 * Spring's resource-server filter verifies the signature, expiry and issuer, runs the account check below
 * and turns the "role" claim into ROLE_xxx. Which role may call which controller is decided by
 * RoleCheckAspect from the @RequiredRole annotation.
 */
@Configuration
public class SecurityConfig {

    @Value("${gatekeeper.security.jwt.issuer}")
    private String issuer;

    @Value("${gatekeeper.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    private final JwtKeys jwtKeys;

    public SecurityConfig(JwtKeys jwtKeys) {
        this.jwtKeys = jwtKeys;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter roleConverter = new JwtGrantedAuthoritiesConverter();
        roleConverter.setAuthoritiesClaimName(AuthService.CLAIM_ROLE);
        roleConverter.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
        jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(roleConverter);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/health", "/error").permitAll()
                        // served only on the internal management port (management.server.port)
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        // public signing key, so other services (analytics) can verify our tokens themselves
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        // role checks happen per controller in RoleCheckAspect (@RequiredRole)
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder(@Lazy AuthService authService) {
        // a token stays valid only while its user is active and their organization is not suspended
        OAuth2TokenValidator<Jwt> accountActive = jwt -> {
            boolean usable = jwt.getClaim(AuthService.CLAIM_USER_ID) instanceof Number userId
                    && authService.isAccountActive(userId.longValue());
            return usable
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
                    "Account is inactive or organization is suspended", null));
        };

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(jwtKeys.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), accountActive));
        return decoder;
    }

    // The signing key with a key ID (its thumbprint), so tokens carry a "kid" that verifiers match against the JWKS
    @Bean
    public JWKSet signingKeys() throws JOSEException {
        return new JWKSet(new RSAKey.Builder(jwtKeys.publicKey()).privateKey(jwtKeys.privateKey()).keyIDFromThumbprint().build());
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSet signingKeys) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(signingKeys));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(allowedOrigins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
