package in.code.gatekeeper.gateway;

import in.code.gatekeeper.gateway.support.GatewayIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotent routes (decisions #47, #62, #72): a repeated request with the same Idempotency-Key gets the first answer
 * again instead of running twice; a concurrent duplicate gets 409; any upstream failure frees the key; the key is
 * always forwarded so the backend can deduplicate too. While Redis is down the gateway's check is bypassed (marked
 * "unchecked") and requests keep flowing; once Redis is back keys are checked again.
 */
class IdempotencyTest extends GatewayIntegrationTest {

    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = newTenant("idem");
        awaitVersion(tenant, publish(tenant, "ACTIVE", List.of(
                route(tenant, "orders", "/orders", Set.of("POST"), null, true),
                route(tenant, "slow", "/slow", Set.of("POST"), null, true),
                route(tenant, "fail", "/fail", Set.of("POST"), null, true))));
    }

    @Test
    void repeatGetsTheFirstAnswerAndTheBackendRunsOnce() {
        String key = UUID.randomUUID().toString();
        BACKEND.clear();
        Response first = post("/orders", "alice", key, "{\"item\":1}");
        Response repeat = post("/orders", "alice", key, "{\"item\":1}");

        assertThat(first.status()).isEqualTo(200);
        assertThat(repeat.status()).isEqualTo(200);
        assertThat(repeat.body()).isEqualTo(first.body());
        assertThat(repeat.header("Idempotency-Replayed")).isEqualTo("true");
        assertThat(BACKEND).hasSize(1);
        // forwarded so the backend can deduplicate as well (#62)
        assertThat(BACKEND.getFirst().headers().get("idempotency-key")).containsExactly(key);
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() {
        String key = UUID.randomUUID().toString();
        assertThat(post("/orders", "alice", key, "{\"item\":1}").status()).isEqualTo(200);
        assertThat(post("/orders", "alice", key, "{\"item\":2}").status()).isEqualTo(422);
    }

    @Test
    void keyIsRequiredOnIdempotentRoutes() {
        assertThat(request("POST", "/" + tenant.slug() + "/orders",
                Map.of("Authorization", "Bearer " + tenant.userToken("alice")), "{}").status()).isEqualTo(400);
    }

    @Test
    void keysBelongToOneUser() {
        String key = UUID.randomUUID().toString();
        BACKEND.clear();
        post("/orders", "alice", key, "{}");
        Response bob = post("/orders", "bob", key, "{}");
        assertThat(bob.header("Idempotency-Replayed")).isNull();
        assertThat(BACKEND).hasSize(2);
    }

    @Test
    void concurrentDuplicateGets409AndRunsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        BACKEND.clear();
        List<Response> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Response> first = pool.submit(() -> post("/slow", "alice", key, "{}"));
            sleep(80);   // the first holds the key while the backend takes 300 ms
            Future<Response> second = pool.submit(() -> post("/slow", "alice", key, "{}"));
            responses.add(first.get());
            responses.add(second.get());
        }
        assertThat(responses.get(0).status()).isEqualTo(200);
        assertThat(responses.get(1).status()).isEqualTo(409);
        assertThat(responses.get(1).header("Retry-After")).isNotNull();
        assertThat(BACKEND).hasSize(1);
    }

    @Test
    void upstreamFailureFreesTheKey() {
        String key = UUID.randomUUID().toString();
        BACKEND.clear();
        assertThat(post("/fail", "alice", key, "{}").status()).isGreaterThanOrEqualTo(500);
        int afterFirst = BACKEND.size();
        // safe to retry with the same key: it runs again instead of replaying the failure or answering 409
        assertThat(post("/fail", "alice", key, "{}").status()).isGreaterThanOrEqualTo(500);
        assertThat(BACKEND.size()).isGreaterThan(afterFirst);
    }

    @Test
    void whileRedisIsDownRequestsFlowUncheckedAndCheckingResumesAfterwards() {
        REDIS_PAUSE.run();
        try {
            BACKEND.clear();
            List<Response> during = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                during.add(post("/orders", "alice", "down-" + i + "-" + UUID.randomUUID(), "{}"));
            }
            assertThat(during).allSatisfy(response -> assertThat(response.status()).isEqualTo(200));
            // once the breaker is open every answer says the gateway didn't check the key
            assertThat(during.getLast().header("Idempotency-Status")).isEqualTo("unchecked");
            assertThat(BACKEND).allSatisfy(received -> assertThat(received.headers()).containsKey("idempotency-key"));
        } finally {
            REDIS_UNPAUSE.run();
        }

        // the background probe closes the breaker; then repeats are replayed again
        String key = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + 20_000;
        Response repeat = null;
        while (System.currentTimeMillis() < deadline) {
            post("/orders", "alice", key, "{}");
            repeat = post("/orders", "alice", key, "{}");
            if ("true".equals(repeat.header("Idempotency-Replayed"))) {
                break;
            }
            key = UUID.randomUUID().toString();
            sleep(500);
        }
        assertThat(repeat.header("Idempotency-Replayed")).isEqualTo("true");
    }

    private Response post(String routePath, String user, String key, String body) {
        return request("POST", "/" + tenant.slug() + routePath, Map.of(
                "Authorization", "Bearer " + tenant.userToken(user),
                "Idempotency-Key", key,
                "Content-Type", "application/json"), body);
    }
}
