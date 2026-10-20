package in.code.gatekeeper.management.config;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the public JWKS (with the kid tokens carry) to a file on every start, for verifiers that can't fetch
 * /.well-known/jwks.json: Grafana only accepts a JWKS URL over https, so in Docker it reads this file instead.
 */
@Slf4j
@Component
public class PublicJwksFile {

    private final JWKSet signingKeys;
    private final String location;

    public PublicJwksFile(JWKSet signingKeys, @Value("${gatekeeper.security.jwt.public-jwks-file:}") String location) {
        this.signingKeys = signingKeys;
        this.location = location;
    }

    @EventListener(ApplicationReadyEvent.class)
    void write() {
        if (location.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(location), signingKeys.toPublicJWKSet().toString());
        } catch (IOException e) {
            log.warn("Could not write the public JWKS to {}: {}", location, e.toString());
        }
    }
}
