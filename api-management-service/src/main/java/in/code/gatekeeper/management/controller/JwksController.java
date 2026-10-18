package in.code.gatekeeper.management.controller;

import com.nimbusds.jose.jwk.JWKSet;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the PUBLIC half of the token signing key (standard JWKS format). Services such as analytics verify
 * tokens issued here with it, without a shared database or a call per request. The private key is never exposed.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JWKSet signingKeys;

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(signingKeys.toPublicJWKSet().toJSONObject());
    }
}
