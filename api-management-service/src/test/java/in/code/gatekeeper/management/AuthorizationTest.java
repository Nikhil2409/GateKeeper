package in.code.gatekeeper.management;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import in.code.gatekeeper.management.support.IntegrationTest;
import in.code.gatekeeper.management.support.TestApi;
import in.code.gatekeeper.management.support.TestApi.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.converter.RsaKeyConverters;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static in.code.gatekeeper.management.support.TestApi.expect;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may call what (decisions #1, #3, #4): no token or a token we didn't sign or that expired -> 401; the wrong
 * role -> 403; a suspended organization or deleted admin loses access at once, even with an unexpired token.
 */
class AuthorizationTest extends IntegrationTest {

    private static final String PLATFORM_ENDPOINT = "/api/platform/organizations";
    private static final String TENANT_ENDPOINT = "/api/tenant/routes";

    private TestApi api;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        api = api();
        tenant = api.createTenant("auth");
    }

    @Test
    void publicEndpointsNeedNoToken() {
        assertThat(api.get("/health", null).status()).isEqualTo(200);
        JsonNode jwks = expect(200, api.get("/.well-known/jwks.json", null)).body();
        assertThat(jwks.get("keys")).hasSize(1);
        // the public key only: no private RSA parameters ever leave the service
        assertThat(jwks.get("keys").get(0).has("d")).isFalse();
        assertThat(jwks.get("keys").get(0).has("p")).isFalse();
    }

    @Test
    void everythingElseNeedsAToken() {
        assertThat(api.get(PLATFORM_ENDPOINT, null).status()).isEqualTo(401);
        assertThat(api.get(TENANT_ENDPOINT, null).status()).isEqualTo(401);
        assertThat(api.get(TENANT_ENDPOINT, "not-a-jwt").status()).isEqualTo(401);
    }

    @Test
    void wrongPasswordIsRefused() {
        assertThat(api.login(TestApi.PLATFORM_ADMIN, "wrong-password").status()).isEqualTo(401);
        assertThat(api.login("nobody", "whatever-123").status()).isEqualTo(401);
    }

    @Test
    void tokenSignedByAnotherKeyIsRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String forged = sign(generator.generateKeyPair().getPrivate(), "gatekeeper-api-management",
                Instant.now().plus(Duration.ofMinutes(10)), "PLATFORM_ADMIN", 1L, null);
        assertThat(api.get(PLATFORM_ENDPOINT, forged).status()).isEqualTo(401);
    }

    @Test
    void expiredTokenIsRefused() throws Exception {
        String expired = sign(serviceKey(), "gatekeeper-api-management",
                Instant.now().minus(Duration.ofMinutes(5)), "PLATFORM_ADMIN", 1L, null);
        assertThat(api.get(PLATFORM_ENDPOINT, expired).status()).isEqualTo(401);
    }

    @Test
    void tokenFromAnotherIssuerIsRefused() throws Exception {
        String foreign = sign(serviceKey(), "someone-else", Instant.now().plus(Duration.ofMinutes(10)),
                "PLATFORM_ADMIN", 1L, null);
        assertThat(api.get(PLATFORM_ENDPOINT, foreign).status()).isEqualTo(401);
    }

    @Test
    void tenantAdminCantUsePlatformApis() {
        assertThat(api.get(PLATFORM_ENDPOINT, tenant.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/platform/tenant-admins", tenant.token()).status()).isEqualTo(403);
        assertThat(api.get("/api/platform/kafka", tenant.token()).status()).isEqualTo(403);
        assertThat(api.call("PATCH", "/api/platform/organizations/" + tenant.organizationId() + "/suspend",
                tenant.token(), null).status()).isEqualTo(403);
    }

    @Test
    void platformAdminCantUseTenantApis() {
        // a platform admin belongs to no organization, so there is nothing for tenant APIs to act on
        String platform = api.platformToken();
        assertThat(api.get(TENANT_ENDPOINT, platform).status()).isEqualTo(403);
        assertThat(api.get("/api/tenant/configuration/versions", platform).status()).isEqualTo(403);
    }

    @Test
    void suspendingAnOrganizationCutsOffItsAdminAtOnce() {
        String platform = api.platformToken();
        expect(200, api.get(TENANT_ENDPOINT, tenant.token()));

        expect(200, api.call("PATCH", "/api/platform/organizations/" + tenant.organizationId() + "/suspend",
                platform, null));
        assertThat(api.get(TENANT_ENDPOINT, tenant.token()).status()).isEqualTo(401);
        assertThat(api.login(tenant.adminUsername(), TestApi.TENANT_PASSWORD).status()).isEqualTo(403);

        expect(200, api.call("PATCH", "/api/platform/organizations/" + tenant.organizationId() + "/activate",
                platform, null));
        expect(200, api.get(TENANT_ENDPOINT, tenant.token()));
    }

    @Test
    void deletedAdminLosesAccessAtOnce() {
        expect(204, api.call("DELETE", "/api/platform/tenant-admins/" + tenant.adminId(), api.platformToken(), null));
        assertThat(api.get(TENANT_ENDPOINT, tenant.token()).status()).isEqualTo(401);
        assertThat(api.login(tenant.adminUsername(), TestApi.TENANT_PASSWORD).status()).isIn(401, 403);
    }

    // The key the running service signs with (generated into the test's temporary key folder)
    private static PrivateKey serviceKey() throws Exception {
        try (InputStream pem = Files.newInputStream(KEYS.resolve("private.pem"))) {
            return RsaKeyConverters.pkcs8().convert(pem);
        }
    }

    private static String sign(PrivateKey key, String issuer, Instant expiresAt, String role, Long userId,
                               Long organizationId) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(TestApi.PLATFORM_ADMIN)
                .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(15))))
                .expirationTime(Date.from(expiresAt))
                .claim("uid", userId)
                .claim("role", role);
        if (organizationId != null) {
            claims.claim("org_id", organizationId);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
