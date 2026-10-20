package in.code.gatekeeper.gateway;

import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration arrives as whole-organization messages with a version (decisions #11, #55): a newer version replaces
 * the organization's routes at once, an older or repeated version is ignored, so duplicates and reordering on the
 * topic can never roll a gateway back. Versions are per organization.
 */
class ConfigOrderingTest extends GatewayIntegrationTest {

    private Tenant tenant;
    private String token;

    @BeforeEach
    void setUp() {
        tenant = newTenant("ord");
        token = tenant.userToken("alice");
    }

    @Test
    void newerVersionIsAppliedWithoutRestart() {
        awaitVersion(tenant, publish(tenant, "ACTIVE", List.of(route("first", "/first"))));
        assertThat(get(path("/first"), token).status()).isEqualTo(200);
        assertThat(get(path("/second"), token).status()).isEqualTo(404);

        // the whole organization is replaced: /first is gone, /second is new
        awaitVersion(tenant, publish(tenant, "ACTIVE", List.of(route("second", "/second"))));
        assertThat(get(path("/second"), token).status()).isEqualTo(200);
        assertThat(get(path("/first"), token).status()).isEqualTo(404);
    }

    @Test
    void olderVersionArrivingLateIsIgnored() {
        publishVersion(tenant, "ACTIVE", List.of(route("current", "/current")), 50);
        awaitVersion(tenant, 50);
        Tenant marker = newTenant("ord-marker");

        publishVersion(tenant, "ACTIVE", List.of(route("stale", "/stale")), 49);
        // the marker is published after the stale message, so once it is applied the stale one has been read too
        awaitVersion(marker, publish(marker, "ACTIVE", List.of()));

        assertThat(get(path("/current"), token).status()).isEqualTo(200);
        assertThat(get(path("/stale"), token).status()).isEqualTo(404);
    }

    @Test
    void sameVersionAgainIsIgnoredEvenWithDifferentContent() {
        publishVersion(tenant, "ACTIVE", List.of(route("current", "/current")), 70);
        awaitVersion(tenant, 70);
        Tenant marker = newTenant("ord-marker");

        publishVersion(tenant, "SUSPENDED", List.of(route("other", "/other")), 70);
        awaitVersion(marker, publish(marker, "ACTIVE", List.of()));

        assertThat(get(path("/current"), token).status()).isEqualTo(200);
        assertThat(get(path("/other"), token).status()).isEqualTo(404);
    }

    @Test
    void versionsArePerOrganization() {
        Tenant other = newTenant("ord-other");
        publishVersion(tenant, "ACTIVE", List.of(route("mine", "/mine")), 90);
        awaitVersion(tenant, 90);

        // a low version of ANOTHER organization is that organization's first configuration, not "older"
        publishVersion(other, "ACTIVE", List.of(route("theirs", "/theirs")), 2);
        awaitVersion(other, 2);
        assertThat(get("/" + other.slug() + "/theirs", other.userToken("bob")).status()).isEqualTo(200);
        assertThat(get(path("/mine"), token).status()).isEqualTo(200);
    }

    private String path(String routePath) {
        return "/" + tenant.slug() + routePath;
    }

    private RouteConfig route(String routeId, String path) {
        return route(tenant, routeId, path, Set.of("GET"), null, false);
    }
}
