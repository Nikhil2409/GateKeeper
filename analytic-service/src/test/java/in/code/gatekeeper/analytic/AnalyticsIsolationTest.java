package in.code.gatekeeper.analytic;

import in.code.gatekeeper.analytic.document.OrganizationDocument;
import in.code.gatekeeper.analytic.document.OrganizationDocument.RouteLimit;
import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.repository.OrganizationRepository;
import in.code.gatekeeper.analytic.repository.TrafficEventRepository;
import in.code.gatekeeper.analytic.service.TrafficRollupService;
import in.code.gatekeeper.analytic.support.AnalyticsIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tenant admin only ever sees their own organization's traffic, whatever they ask for (decisions #25, #67):
 * the organization comes from the signed token and is forced into every query, live metrics included. Platform
 * admins see everything and may narrow it down. Tokens not issued by api-management are refused.
 */
class AnalyticsIsolationTest extends AnalyticsIntegrationTest {

    @Autowired
    private TrafficEventRepository events;
    @Autowired
    private OrganizationRepository organizations;
    @Autowired
    private TrafficRollupService rollup;

    private long a;
    private long b;
    private String tenantA;

    @BeforeEach
    void twoOrganizationsWithTraffic() {
        a = ThreadLocalRandom.current().nextLong(1_000_000, 2_000_000_000);
        b = a + 1;
        organization(a, "a-" + a);
        organization(b, "b-" + b);
        traffic(a, "a-" + a, "orders", 3);
        traffic(b, "b-" + b, "secret", 5);
        rollup.rollUp();
        tenantA = token("TENANT_ADMIN", a);
    }

    @Test
    void summaryCountsOnlyOwnTrafficEvenWhenAskingForAnother() {
        assertThat(get("/analytics/summary?range=1h", tenantA).body().get("requests").asLong()).isEqualTo(3);
        assertThat(get("/analytics/summary?range=1h&org=" + b, tenantA).body().get("requests").asLong()).isEqualTo(3);
    }

    @Test
    void routesTimeseriesAndRequestLogShowOnlyOwnOrganization() {
        assertThat(slugs(get("/analytics/routes?range=1h&org=" + b, tenantA).body())).containsOnly("a-" + a);

        long chartTotal = 0;
        for (JsonNode bucket : get("/analytics/timeseries?range=1h&org=" + b, tenantA).body()) {
            chartTotal += bucket.get("requests").asLong();
        }
        assertThat(chartTotal).isEqualTo(3);

        JsonNode log = get("/analytics/requests?range=1h&org=" + b, tenantA).body();
        assertThat(log.get("total").asLong()).isEqualTo(3);
        assertThat(slugs(log.get("items"))).containsOnly("a-" + a);
    }

    @Test
    void policyViewsShowOnlyOwnRoutes() {
        assertThat(slugs(get("/analytics/rate-limits?org=" + b, tenantA).body())).containsOnly("a-" + a);
        for (JsonNode outcome : get("/analytics/idempotency?org=" + b, tenantA).body().get("recent")) {
            assertThat(outcome.get("organizationSlug").asString()).isEqualTo("a-" + a);
        }
    }

    @Test
    void liveMetricsQueriesAreBuiltForTheTokensOrganization() {
        PROMETHEUS_QUERIES.clear();
        assertThat(get("/analytics/live/routes?org=" + b, tenantA).status()).isEqualTo(200);
        assertThat(get("/analytics/live/timeseries?org=" + b, tenantA).status()).isEqualTo(200);

        assertThat(PROMETHEUS_QUERIES).isNotEmpty();
        assertThat(PROMETHEUS_QUERIES).allSatisfy(query -> {
            assertThat(query).contains("organization_id=\"" + a + "\"");
            assertThat(query).doesNotContain("organization_id=\"" + b + "\"");
        });
    }

    @Test
    void organizationsOverviewIsForPlatformAdminsOnly() {
        assertThat(get("/analytics/organizations?range=1h", tenantA).status()).isEqualTo(403);
        JsonNode all = get("/analytics/organizations?range=1h", token("PLATFORM_ADMIN", null)).body();
        assertThat(slugs(all)).contains("a-" + a, "b-" + b);
    }

    @Test
    void platformAdminSeesEverythingAndCanNarrowDown() {
        String platform = token("PLATFORM_ADMIN", null);
        assertThat(get("/analytics/summary?range=1h&org=" + b, platform).body().get("requests").asLong()).isEqualTo(5);
        assertThat(get("/analytics/summary?range=1h", platform).body().get("requests").asLong()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void tenantTokenWithoutOrganizationIsRefused() {
        assertThat(get("/analytics/summary?range=1h", token("TENANT_ADMIN", null)).status()).isEqualTo(403);
    }

    @Test
    void onlyApiManagementsValidTokensAreAccepted() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        Instant later = Instant.now().plus(Duration.ofMinutes(10));

        assertThat(get("/analytics/summary", null).status()).isEqualTo(401);
        assertThat(get("/analytics/summary", sign(generator.generateKeyPair().getPrivate(), ISSUER,
                "PLATFORM_ADMIN", null, later)).status()).isEqualTo(401);
        assertThat(get("/analytics/summary", sign(SIGNING_KEY.getPrivate(), "someone-else",
                "PLATFORM_ADMIN", null, later)).status()).isEqualTo(401);
        assertThat(get("/analytics/summary", sign(SIGNING_KEY.getPrivate(), ISSUER, "PLATFORM_ADMIN", null,
                Instant.now().minus(Duration.ofMinutes(5)))).status()).isEqualTo(401);
    }

    private void organization(long id, String slug) {
        organizations.save(new OrganizationDocument(id, slug, slug, "ACTIVE", 1,
                List.of(new RouteLimit(slug.startsWith("a-") ? "orders" : "secret", 60, 6, true)), Instant.now()));
    }

    private void traffic(long organizationId, String slug, String routeId, int count) {
        Instant minuteAgo = Instant.now().minus(Duration.ofMinutes(1));
        for (int i = 0; i < count; i++) {
            events.save(new TrafficEventDocument(UUID.randomUUID().toString(), "corr-" + i, minuteAgo,
                    "smart-gateway-1", organizationId, slug, routeId, "POST", "/" + slug + "/" + routeId,
                    i == 0 ? 429 : 200, 5 + i, "10.0.0.1", i == 0 ? "RATE_LIMITED" : null,
                    i == 1 ? "REPLAYED" : "EXECUTED", "key-" + slug + "-" + i, Instant.now()));
        }
    }

    private static List<String> slugs(JsonNode rows) {
        List<String> slugs = new ArrayList<>();
        rows.forEach(row -> slugs.add(row.get("organizationSlug").asString()));
        return slugs;
    }
}
