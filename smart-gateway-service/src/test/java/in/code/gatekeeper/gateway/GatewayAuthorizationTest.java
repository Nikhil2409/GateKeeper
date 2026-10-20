package in.code.gatekeeper.gateway;

import in.code.gatekeeper.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each organization trusts only its own identity provider (decisions #26, #27, #48): a token signed for tenant A is
 * worthless on tenant B's routes, the user id the backend sees is the verified token subject (a caller can't set it),
 * and only configured METHOD + path pass. A suspended organization's routes stop being served.
 */
class GatewayAuthorizationTest extends GatewayIntegrationTest {

    private Tenant a;
    private Tenant b;

    @BeforeEach
    void twoTenants() {
        a = newTenant("gwa");
        b = newTenant("gwb");
        awaitVersion(a, publish(a, "ACTIVE", List.of(route(a, "hello", "/hello", Set.of("GET"), null, false))));
        awaitVersion(b, publish(b, "ACTIVE", List.of(route(b, "hello", "/hello", Set.of("GET"), null, false))));
    }

    @Test
    void ownUsersTokenIsForwardedWithTheVerifiedUserId() {
        BACKEND.clear();
        Response response = request("GET", "/" + a.slug() + "/hello", Map.of(
                "Authorization", "Bearer " + a.userToken("alice"),
                // a caller can never choose the user id the backend sees
                "X-User-Id", "admin"), null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(BACKEND).hasSize(1);
        assertThat(BACKEND.getFirst().path()).isEqualTo("/backend/" + a.slug() + "/hello");
        assertThat(BACKEND.getFirst().headers().get("x-user-id")).containsExactly("alice");
    }

    @Test
    void anotherTenantsTokenIsRefused() {
        BACKEND.clear();
        assertThat(get("/" + b.slug() + "/hello", a.userToken("alice")).status()).isEqualTo(401);
        assertThat(BACKEND).isEmpty();
    }

    @Test
    void tokenSignedByTheWrongProviderIsRefusedEvenWithTheRightClaims() {
        // A's key, but B's issuer and audience: the signature doesn't match B's keys
        String forged = token(a.key(), b.issuer(), b.audience(), "mallory", Instant.now().plus(Duration.ofMinutes(5)));
        assertThat(get("/" + b.slug() + "/hello", forged).status()).isEqualTo(401);
    }

    @Test
    void wrongAudienceExpiredOrMissingTokenIsRefused() {
        String path = "/" + a.slug() + "/hello";
        Instant later = Instant.now().plus(Duration.ofMinutes(5));
        assertThat(get(path, token(a.key(), a.issuer(), "other-api", "alice", later)).status()).isEqualTo(401);
        assertThat(get(path, token(a.key(), "http://elsewhere", a.audience(), "alice", later)).status()).isEqualTo(401);
        assertThat(get(path, token(a.key(), a.issuer(), a.audience(), "alice",
                Instant.now().minus(Duration.ofMinutes(5)))).status()).isEqualTo(401);
        assertThat(get(path, null).status()).isEqualTo(401);
    }

    @Test
    void onlyConfiguredMethodsAndPathsPass() {
        String token = a.userToken("alice");
        assertThat(get("/" + a.slug() + "/not-configured", token).status()).isEqualTo(404);
        assertThat(get("/no-such-organization/hello", token).status()).isEqualTo(404);
        assertThat(request("POST", "/" + a.slug() + "/hello", Map.of("Authorization", "Bearer " + token), "{}")
                .status()).isEqualTo(405);
        assertThat(get("/" + a.slug() + "/hello/../../" + b.slug() + "/hello", token).status()).isIn(400, 404);
    }

    @Test
    void suspendedOrganizationIsNoLongerServed() {
        String token = a.userToken("alice");
        assertThat(get("/" + a.slug() + "/hello", token).status()).isEqualTo(200);

        awaitVersion(a, publish(a, "SUSPENDED", List.of(route(a, "hello", "/hello", Set.of("GET"), null, false))));
        assertThat(get("/" + a.slug() + "/hello", token).status()).isEqualTo(404);
    }
}
