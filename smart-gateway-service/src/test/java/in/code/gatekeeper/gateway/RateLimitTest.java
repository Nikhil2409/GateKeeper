package in.code.gatekeeper.gateway;

import in.code.gatekeeper.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rate limits and per-user quotas hold across gateway instances, because the counters live in Redis (decisions #28,
 * #29, #47). Besides the test's own gateway, two more real gateway instances run on the same Redis and Kafka, and
 * requests go round robin over all three, like nginx does. While Redis is down each instance enforces its share of
 * the limit (limit / gateway.cluster-size) on its own (#72).
 */
@TestPropertySource(properties = "gateway.cluster-size=3")
class RateLimitTest extends GatewayIntegrationTest {

    private static final int ROUTE_LIMIT = 30;          // per user: a tenth, 3
    private static final List<ConfigurableApplicationContext> EXTRA = new ArrayList<>();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;
    private final List<String> instances = new ArrayList<>();
    private Tenant tenant;
    private int next;

    @BeforeAll
    static void twoMoreGateways() {
        for (int i = 2; i <= 3; i++) {
            // command-line arguments: they win over application.properties (server.port=8080)
            EXTRA.add(new SpringApplicationBuilder(SmartGatewayServiceApplication.class).run(
                    "--server.port=0", "--management.server.port=0", "--gateway.instance-id=test-gateway-" + i,
                    "--gateway.upstream.allow-loopback=true", "--gateway.cluster-size=3",
                    "--spring.kafka.bootstrap-servers=" + kafkaBootstrap(),
                    "--spring.data.redis.host=" + redisHost(), "--spring.data.redis.port=" + redisPort()));
        }
    }

    @AfterAll
    static void stopThem() {
        EXTRA.forEach(ConfigurableApplicationContext::close);
    }

    @BeforeEach
    void setUp() {
        instances.clear();
        instances.add("http://localhost:" + port);
        EXTRA.forEach(context -> instances.add("http://localhost:" + context.getEnvironment().getProperty("local.server.port")));
        tenant = newTenant("rl");
        long version = publish(tenant, "ACTIVE", List.of(route(tenant, "limited", "/limited", Set.of("GET"), ROUTE_LIMIT, false)));
        instances.forEach(instance -> awaitVersionOn(instance, version));
    }

    @Test
    void routeLimitIsExactAcrossThreeInstances() {
        startEarlyInAMinute();
        List<Integer> statuses = new ArrayList<>();
        // 45 calls from 45 different users, so only the route limit (30/min) can apply
        for (int i = 0; i < 45; i++) {
            statuses.add(call("user-" + i).statusCode());
        }
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(ROUTE_LIMIT);
        assertThat(statuses.stream().filter(s -> s == 429).count()).isEqualTo(15);
    }

    @Test
    void perUserQuotaIsExactAcrossThreeInstances() {
        startEarlyInAMinute();
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            responses.add(call("bob"));
        }
        assertThat(responses.stream().filter(r -> r.statusCode() == 200).count()).isEqualTo(3);
        assertThat(responses.stream().filter(r -> r.statusCode() == 429))
                .hasSize(7)
                .allSatisfy(r -> {
                    assertThat(r.body()).contains("quota");
                    assertThat(r.headers().firstValue("Retry-After")).isPresent();
                });
    }

    @Test
    void whileRedisIsDownEachInstanceEnforcesItsShare() {
        startEarlyInAMinute();
        REDIS_PAUSE.run();
        List<Integer> statuses = new ArrayList<>();
        try {
            // 15 calls per instance, all different users: each instance may let through 30 / 3 = 10
            for (int i = 0; i < 45; i++) {
                statuses.add(call("down-user-" + i).statusCode());
            }
        } finally {
            REDIS_UNPAUSE.run();
            awaitRedisBackOnEveryInstance();
        }
        assertThat(statuses).containsOnly(200, 429);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(ROUTE_LIMIT);
    }

    // Until every instance's breaker has closed, some count locally and some in Redis (a known few-second overshoot
    // while recovering), so the next test must not start before
    private void awaitRedisBackOnEveryInstance() {
        long deadline = System.currentTimeMillis() + 30_000;
        for (String instance : instances) {
            while (System.currentTimeMillis() < deadline) {
                try {
                    HttpResponse<String> health = HTTP.send(HttpRequest.newBuilder(URI.create(instance + "/_gateway/health"))
                            .GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (health.body().contains("\"redis\":\"UP\"")) {
                        break;
                    }
                } catch (IOException e) {
                    // retry
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                sleep(500);
            }
        }
        sleep(3_000);   // one probe interval (2 s) for the breakers to close
    }

    // round robin over the three instances, like nginx
    private HttpResponse<String> call(String user) {
        String instance = instances.get(next++ % instances.size());
        HttpRequest request = HttpRequest.newBuilder(URI.create(instance + "/" + tenant.slug() + "/limited"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + tenant.userToken(user)).GET().build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void awaitVersionOn(String instance, long version) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> status = HTTP.send(HttpRequest.newBuilder(URI.create(instance + "/_gateway/status"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                if (status.body().contains("\"slug\":\"" + tenant.slug() + "\",\"version\":" + version)) {
                    return;
                }
            } catch (IOException e) {
                // not up yet
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            sleep(100);
        }
        throw new AssertionError(instance + " never applied " + tenant.slug() + " v" + version);
    }

    // counters are per clock minute: start with enough of the minute left so a test never spans two windows
    private static void startEarlyInAMinute() {
        long second = Instant.now().getEpochSecond() % 60;
        if (second > 35) {
            sleep((61 - second) * 1000);
        }
    }
}
