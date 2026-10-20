package in.code.gatekeeper.management;

import in.code.gatekeeper.management.support.IntegrationTest;
import in.code.gatekeeper.management.support.PublishedConfigs;
import in.code.gatekeeper.management.support.TestApi;
import in.code.gatekeeper.management.support.TestApi.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static in.code.gatekeeper.management.support.TestApi.expect;
import static in.code.gatekeeper.management.support.TestApi.route;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One version per organization, bumped on every change and published with the COMPLETE configuration (decisions
 * #9, #10, #11); concurrent changes never share a version; the last 5 content versions are kept; rollback publishes
 * the old content as a NEW, higher version (#63), so a gateway's "newer wins" rule never needs an exception.
 */
class ConfigVersioningTest extends IntegrationTest {

    private TestApi api;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        api = api();
        tenant = api.createTenant("ver");
    }

    @Test
    void everyChangePublishesTheCompleteConfigurationUnderANewVersion() {
        api.createRoute(tenant, route("orders", "/orders", "http://orders.v1.example:8080"));
        api.createRoute(tenant, route("items", "/items", "http://items.example:8080"));
        expect(200, api.call("PUT", "/api/tenant/routes/orders", tenant.token(),
                route("orders", "/orders", "http://orders.v2.example:8080")));

        long current = currentVersion();
        JsonNode latest = PublishedConfigs.latest(kafka(), tenant.organizationId(), current);
        assertThat(latest.get("configurationVersion").asLong()).isEqualTo(current);
        assertThat(targets(latest)).containsExactlyInAnyOrder("http://orders.v2.example:8080", "http://items.example:8080");

        List<Long> versions = new ArrayList<>();
        PublishedConfigs.all(kafka(), tenant.organizationId())
                .forEach(message -> versions.add(message.get("configurationVersion").asLong()));
        assertThat(versions).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void concurrentChangesNeverShareAVersion() throws Exception {
        long before = currentVersion();
        int changes = 8;
        try (ExecutorService pool = Executors.newFixedThreadPool(changes)) {
            List<Future<TestApi.Response>> results = new ArrayList<>();
            for (int i = 0; i < changes; i++) {
                int n = i;
                results.add(pool.submit(() -> api.call("POST", "/api/tenant/routes", tenant.token(),
                        route("parallel-" + n, "/parallel-" + n, "http://parallel.example:8080"))));
            }
            for (Future<TestApi.Response> result : results) {
                assertThat(result.get().status()).isEqualTo(201);
            }
        }

        assertThat(currentVersion()).isEqualTo(before + changes);
        JsonNode latest = PublishedConfigs.latest(kafka(), tenant.organizationId(), before + changes);
        assertThat(latest.get("routes")).hasSize(changes);

        Set<Long> published = new HashSet<>();
        PublishedConfigs.all(kafka(), tenant.organizationId())
                .forEach(message -> assertThat(published.add(message.get("configurationVersion").asLong()))
                        .as("version published twice").isTrue());
    }

    @Test
    void historyKeepsTheLastFiveContentVersions() {
        for (int i = 1; i <= 7; i++) {
            api.createRoute(tenant, route("r" + i, "/r" + i, "http://r" + i + ".example:8080"));
        }
        JsonNode history = expect(200, api.get("/api/tenant/configuration/versions", tenant.token())).body();
        assertThat(history).hasSize(5);

        long current = currentVersion();
        List<Long> kept = new ArrayList<>();
        history.forEach(version -> kept.add(version.get("version").asLong()));
        assertThat(kept).containsExactlyInAnyOrder(current, current - 1, current - 2, current - 3, current - 4);
        assertThat(api.get("/api/tenant/configuration/versions/" + (current - 5), tenant.token()).status())
                .isEqualTo(404);
    }

    @Test
    void suspendingBumpsTheVersionButIsNotAContentVersion() {
        api.createRoute(tenant, route("orders", "/orders", "http://orders.example:8080"));
        int historyBefore = expect(200, api.get("/api/tenant/configuration/versions", tenant.token())).body().size();
        long before = currentVersion();
        String platform = api.platformToken();

        expect(200, api.call("PATCH", "/api/platform/organizations/" + tenant.organizationId() + "/suspend",
                platform, null));
        assertThat(PublishedConfigs.latest(kafka(), tenant.organizationId(), before + 1).get("status").asString())
                .isEqualTo("SUSPENDED");
        expect(200, api.call("PATCH", "/api/platform/organizations/" + tenant.organizationId() + "/activate",
                platform, null));
        assertThat(PublishedConfigs.latest(kafka(), tenant.organizationId(), before + 2).get("status").asString())
                .isEqualTo("ACTIVE");

        assertThat(currentVersion()).isEqualTo(before + 2);
        String token = api.token(tenant.adminUsername(), TestApi.TENANT_PASSWORD);
        assertThat(expect(200, api.get("/api/tenant/configuration/versions", token)).body()).hasSize(historyBefore);
    }

    @Test
    void rollbackPublishesTheOldContentAsANewHigherVersion() {
        api.createRoute(tenant, route("orders", "/orders", "http://orders.v1.example:8080"));
        long target = currentVersion();
        expect(200, api.call("PUT", "/api/tenant/routes/orders", tenant.token(),
                route("orders", "/orders", "http://orders.v2.example:8080")));
        api.createRoute(tenant, route("later", "/later", "http://later.example:8080"));
        long beforeRollback = currentVersion();

        JsonNode preview = expect(200, api.get("/api/tenant/configuration/versions/" + target, tenant.token())).body();
        assertThat(preview.get("changes").get("routesSwitchedOff").toString()).contains("later");
        assertThat(preview.get("changes").get("routesChanged").toString()).contains("orders", "\"field\":\"target\"");

        JsonNode rollback = expect(200, api.call("POST", "/api/tenant/configuration/versions/" + target + "/rollback",
                tenant.token(), null)).body();
        long newVersion = rollback.get("newVersion").asLong();
        assertThat(rollback.get("restoredVersion").asLong()).isEqualTo(target);
        assertThat(newVersion).isGreaterThan(beforeRollback);

        JsonNode published = PublishedConfigs.latest(kafka(), tenant.organizationId(), newVersion);
        assertThat(published.get("configurationVersion").asLong()).isEqualTo(newVersion);
        assertThat(targets(published)).containsExactly("http://orders.v1.example:8080");
        assertThat(expect(200, api.get("/api/tenant/routes/later", tenant.token())).body().get("active").asBoolean())
                .isFalse();
    }

    @Test
    void rollbackToTheCurrentOrAPrunedVersionIsRefused() {
        api.createRoute(tenant, route("orders", "/orders", "http://orders.example:8080"));
        long current = currentVersion();
        assertThat(api.call("POST", "/api/tenant/configuration/versions/" + current + "/rollback",
                tenant.token(), null).status()).isEqualTo(400);
        assertThat(api.call("POST", "/api/tenant/configuration/versions/" + (current + 100) + "/rollback",
                tenant.token(), null).status()).isEqualTo(404);
    }

    private long currentVersion() {
        return expect(200, api.get("/api/platform/organizations/" + tenant.organizationId(), api.platformToken()))
                .body().get("configurationVersion").asLong();
    }

    private static List<String> targets(JsonNode message) {
        List<String> targets = new ArrayList<>();
        message.get("routes").forEach(route -> targets.add(route.get("targetBaseUrl").asString()));
        return targets;
    }

    private static String kafka() {
        return KAFKA.getBootstrapServers();
    }
}
