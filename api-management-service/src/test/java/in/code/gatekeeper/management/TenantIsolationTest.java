package in.code.gatekeeper.management;

import in.code.gatekeeper.management.support.IntegrationTest;
import in.code.gatekeeper.management.support.TestApi;
import in.code.gatekeeper.management.support.TestApi.Response;
import in.code.gatekeeper.management.support.TestApi.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static in.code.gatekeeper.management.support.TestApi.expect;
import static in.code.gatekeeper.management.support.TestApi.route;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant admin works only on their own organization: it comes from the signed token, so another organization's
 * routes, token settings and history can't be read, changed or even confirmed to exist (decision #5).
 * Both tenants use the same route id on purpose, so a lookup that ignored the organization would be caught.
 */
class TenantIsolationTest extends IntegrationTest {

    private TestApi api;
    private Tenant a;
    private Tenant b;

    @BeforeEach
    void twoTenants() {
        api = api();
        a = api.createTenant("iso-a");
        b = api.createTenant("iso-b");
        api.createRoute(a, route("orders", "/orders", "http://orders.a.example:8080"));
        api.createRoute(b, route("orders", "/orders", "http://orders.b.example:8080"));
        api.createRoute(b, route("b-only", "/b-only", "http://b-only.b.example:8080"));
    }

    @Test
    void listsOnlyOwnRoutes() {
        assertThat(targets(expect(200, api.get("/api/tenant/routes", a.token())).body()))
                .containsExactly("http://orders.a.example:8080");
        assertThat(targets(expect(200, api.get("/api/tenant/routes", b.token())).body()))
                .containsExactlyInAnyOrder("http://orders.b.example:8080", "http://b-only.b.example:8080");
    }

    @Test
    void sameRouteIdResolvesToOwnRoute() {
        assertThat(expect(200, api.get("/api/tenant/routes/orders", a.token())).body().get("targetBaseUrl").asString())
                .isEqualTo("http://orders.a.example:8080");
    }

    @Test
    void anotherTenantsRouteIsNotFoundForEveryOperation() {
        assertThat(api.get("/api/tenant/routes/b-only", a.token()).status()).isEqualTo(404);
        assertThat(api.call("PUT", "/api/tenant/routes/b-only", a.token(),
                route("b-only", "/b-only", "http://hijacked.example:8080")).status()).isEqualTo(404);
        assertThat(api.call("DELETE", "/api/tenant/routes/b-only", a.token(), null).status()).isEqualTo(404);
        assertThat(api.call("PATCH", "/api/tenant/routes/b-only/activate", a.token(), null).status()).isEqualTo(404);

        JsonNode untouched = expect(200, api.get("/api/tenant/routes/b-only", b.token())).body();
        assertThat(untouched.get("targetBaseUrl").asString()).isEqualTo("http://b-only.b.example:8080");
        assertThat(untouched.get("active").asBoolean()).isTrue();
    }

    @Test
    void changingOwnRouteLeavesTheOtherTenantsSameIdUntouched() {
        expect(200, api.call("PUT", "/api/tenant/routes/orders", a.token(),
                route("orders", "/orders", "http://orders-v2.a.example:8080")));
        expect(204, api.call("DELETE", "/api/tenant/routes/orders", a.token(), null));

        JsonNode bOrders = expect(200, api.get("/api/tenant/routes/orders", b.token())).body();
        assertThat(bOrders.get("targetBaseUrl").asString()).isEqualTo("http://orders.b.example:8080");
        assertThat(bOrders.get("active").asBoolean()).isTrue();
    }

    @Test
    void tokenSettingsAreSeparate() {
        expect(200, api.call("PUT", "/api/tenant/token-settings", a.token(), Map.of(
                "issuer", "https://login.a.example/", "jwksUri", "https://login.a.example/jwks.json",
                "audience", "a-api")));

        assertThat(api.get("/api/tenant/token-settings", b.token()).status()).isEqualTo(404);
        assertThat(expect(200, api.get("/api/tenant/token-settings", a.token())).body().get("issuer").asString())
                .isEqualTo("https://login.a.example/");
    }

    @Test
    void historyAndPreviewShowOnlyOwnConfiguration() {
        JsonNode versions = expect(200, api.get("/api/tenant/configuration/versions", a.token())).body();
        assertThat(versions).isNotEmpty();
        for (JsonNode version : versions) {
            JsonNode configuration = expect(200, api.get("/api/tenant/configuration/versions/"
                    + version.get("version").asLong(), a.token())).body().get("configuration");
            for (JsonNode route : configuration.get("routes")) {
                assertThat(route.get("route").get("targetBaseUrl").asString()).contains(".a.example");
            }
        }
    }

    @Test
    void rollbackChangesOnlyOwnOrganization() {
        long before = latestVersion(a);
        expect(200, api.call("PUT", "/api/tenant/routes/orders", a.token(),
                route("orders", "/orders", "http://orders-v2.a.example:8080")));

        Response rollback = expect(200, api.call("POST",
                "/api/tenant/configuration/versions/" + before + "/rollback", a.token(), null));
        assertThat(rollback.body().get("newVersion").asLong()).isGreaterThan(before);

        assertThat(expect(200, api.get("/api/tenant/routes/orders", a.token())).body().get("targetBaseUrl").asString())
                .isEqualTo("http://orders.a.example:8080");
        assertThat(targets(expect(200, api.get("/api/tenant/routes", b.token())).body()))
                .containsExactlyInAnyOrder("http://orders.b.example:8080", "http://b-only.b.example:8080");
    }

    @Test
    void organizationInTheRequestIsIgnored() {
        // only the token decides; parameters naming another organization change nothing
        Response response = expect(200, api.get("/api/tenant/routes?organizationId=" + b.organizationId()
                + "&orgId=" + b.organizationId() + "&org=" + b.organizationId(), a.token()));
        assertThat(targets(response.body())).containsExactly("http://orders.a.example:8080");
    }

    private long latestVersion(Tenant tenant) {
        long latest = 0;
        for (JsonNode version : expect(200, api.get("/api/tenant/configuration/versions", tenant.token())).body()) {
            latest = Math.max(latest, version.get("version").asLong());
        }
        return latest;
    }

    private static List<String> targets(JsonNode routes) {
        List<String> targets = new ArrayList<>();
        routes.forEach(route -> {
            if (route.get("active").asBoolean()) {
                targets.add(route.get("targetBaseUrl").asString());
            }
        });
        return targets;
    }
}
