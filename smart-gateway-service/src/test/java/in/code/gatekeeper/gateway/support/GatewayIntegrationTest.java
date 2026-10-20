package in.code.gatekeeper.gateway.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.code.gatekeeper.gateway.model.OrganizationConfig;
import in.code.gatekeeper.gateway.model.RouteConfig;
import in.code.gatekeeper.gateway.model.TokenSettings;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base of gateway integration tests: the real gateway on a random port, real Redis and Kafka in containers shared by
 * all test classes of a run (decision #74). Configuration reaches the gateway the production way, as messages on
 * gateway.org-config (publish()). One small local HTTP server plays every tenant's identity provider (a JWKS per
 * organization) and the tenants' backend, recording what reached it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        // the fake backend and identity providers run on localhost
        "gateway.upstream.allow-loopback=true",
        "gateway.instance-id=test-gateway"})
public abstract class GatewayIntegrationTest {

    static final String CONFIG_TOPIC = "gateway.org-config";
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static final HttpServer FAKES;
    static final KafkaProducer<String, String> PRODUCER;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> JWKS = new ConcurrentHashMap<>();
    private static final AtomicLong VERSIONS = new AtomicLong(1);

    // what the backend received: path and the request headers (lowercase names)
    public record Received(String path, Map<String, List<String>> headers) {
    }

    protected static final List<Received> BACKEND = new CopyOnWriteArrayList<>();

    // Redis outage for one test: a paused container keeps its port but answers nothing (calls time out)
    protected static final Runnable REDIS_PAUSE = () ->
            REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    protected static final Runnable REDIS_UNPAUSE = () ->
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();

    static {
        KAFKA.start();
        REDIS.start();
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(CONFIG_TOPIC, 1, (short) 1)
                    .configs(Map.of("cleanup.policy", "compact")))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        PRODUCER = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName(),
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName()));
        try {
            FAKES = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FAKES.createContext("/idp/", exchange ->
                    respond(exchange, 200, JWKS.getOrDefault(exchange.getRequestURI().getPath(), "{\"keys\":[]}")));
            FAKES.createContext("/backend", GatewayIntegrationTest::backend);
            FAKES.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            FAKES.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();

    // ---------- tenants ----------

    /** A tenant with its own identity provider (key pair + JWKS served by the fake server). */
    public record Tenant(long organizationId, String slug, KeyPair key, String issuer, String audience) {

        public String userToken(String subject) {
            return token(key, issuer, audience, subject, Instant.now().plus(Duration.ofMinutes(10)));
        }
    }

    protected static Tenant newTenant(String prefix) {
        long id = Math.abs(System.nanoTime() % 1_000_000_000L);
        String slug = prefix + "-" + id;
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair key = generator.generateKeyPair();
            String jwksPath = "/idp/" + slug + "/jwks.json";
            JWKS.put(jwksPath, new JWKSet(new RSAKey.Builder((java.security.interfaces.RSAPublicKey) key.getPublic())
                    .keyID(slug).build()).toString());
            return new Tenant(id, slug, key, "http://idp.example/" + slug, slug + "-api");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String kafkaBootstrap() {
        return KAFKA.getBootstrapServers();
    }

    protected static String redisHost() {
        return REDIS.getHost();
    }

    protected static int redisPort() {
        return REDIS.getMappedPort(6379);
    }

    protected static String fakesUrl() {
        return "http://localhost:" + FAKES.getAddress().getPort();
    }

    /** A route forwarded to the fake backend under /backend/{slug}. */
    protected static RouteConfig route(Tenant tenant, String routeId, String path, Set<String> methods,
                                       Integer requestsPerMinute, boolean idempotent) {
        return new RouteConfig(routeId, path, fakesUrl(), "/backend/" + tenant.slug(), methods,
                Set.of("idempotency-key"), requestsPerMinute,
                requestsPerMinute == null ? null : Math.max(1, requestsPerMinute / 10), 3000, idempotent, List.of());
    }

    /** Publishes the tenant's whole configuration with a new, higher version, as api-management would. */
    protected static long publish(Tenant tenant, String status, List<RouteConfig> routes) {
        long version = VERSIONS.incrementAndGet();
        publishVersion(tenant, status, routes, version);
        return version;
    }

    protected static void publishVersion(Tenant tenant, String status, List<RouteConfig> routes, long version) {
        OrganizationConfig config = new OrganizationConfig(tenant.organizationId(), tenant.slug(), version, status,
                new TokenSettings(tenant.issuer(), fakesUrl() + "/idp/" + tenant.slug() + "/jwks.json",
                        tenant.audience()), routes, "test");
        try {
            PRODUCER.send(new ProducerRecord<>(CONFIG_TOPIC, String.valueOf(tenant.organizationId()),
                    JSON.writeValueAsString(config))).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the gateway runs at least this version of the organization (or gives up after 15 s). */
    protected void awaitVersion(Tenant tenant, long version) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Response status = request("GET", "/_gateway/status", Map.of(), null);
            if (status.status() == 200 && status.body().contains("\"slug\":\"" + tenant.slug() + "\",\"version\":" + version)) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("gateway never applied " + tenant.slug() + " v" + version);
    }

    // ---------- requests ----------

    public record Response(int status, String body, Map<String, List<String>> headers) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.getFirst();
        }
    }

    protected Response request(String method, String path, Map<String, String> headers, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Map<String, List<String>> lower = new ConcurrentHashMap<>();
            response.headers().map().forEach((name, values) -> lower.put(name.toLowerCase(), values));
            return new Response(response.statusCode(), response.body(), lower);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected Response get(String path, String token) {
        return request("GET", path, token == null ? Map.of() : Map.of("Authorization", "Bearer " + token), null);
    }

    protected static String token(KeyPair key, String issuer, String audience, String subject, Instant expiresAt) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), new JWTClaimsSet.Builder()
                    .issuer(issuer).audience(audience).subject(subject)
                    .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(15))))
                    .expirationTime(Date.from(expiresAt)).build());
            jwt.sign(new RSASSASigner(key.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // The tenants' backend: 200 {"ok":true} by default; /fail answers 500; /slow waits 300 ms
    private static void backend(HttpExchange exchange) throws IOException {
        Map<String, List<String>> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), List.copyOf(values)));
        String path = exchange.getRequestURI().getPath();
        BACKEND.add(new Received(path, headers));
        exchange.getRequestBody().readAllBytes();
        if (path.endsWith("/slow")) {
            sleep(300);
        }
        respond(exchange, path.endsWith("/fail") ? 500 : 200, "{\"ok\":true,\"n\":" + BACKEND.size() + "}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
