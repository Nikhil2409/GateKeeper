package in.code.gatekeeper.analytic.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Base of analytics integration tests: the real application on a random port and a real MongoDB in a container,
 * both shared by all test classes of a run (decision #74). Instead of api-management and Prometheus, one small local
 * HTTP server serves the token-signing public key (JWKS) and answers Prometheus queries with no data while recording
 * them, so a test can check which organization a live-metrics query was built for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.listener.auto-startup=false",
        "management.server.port=0",
        // tests run the rollup themselves (rollUp()), never on a timer
        "analytics.rollup.interval-ms=3600000"})
public abstract class AnalyticsIntegrationTest {

    public static final String ISSUER = "gatekeeper-api-management";

    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");
    protected static final KeyPair SIGNING_KEY;
    static final HttpServer FAKES;
    protected static final List<String> PROMETHEUS_QUERIES = new CopyOnWriteArrayList<>();

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();

    static {
        MONGO.start();
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            SIGNING_KEY = generator.generateKeyPair();
            String jwks = new JWKSet(new RSAKey.Builder((RSAPublicKey) SIGNING_KEY.getPublic()).build()).toString();
            FAKES = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            FAKES.createContext("/jwks.json", exchange -> respond(exchange, jwks));
            FAKES.createContext("/api/v1/", exchange -> {
                String query = exchange.getRequestURI().getRawQuery();
                PROMETHEUS_QUERIES.add(URLDecoder.decode(query == null ? "" : query, StandardCharsets.UTF_8));
                respond(exchange, "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");
            });
            FAKES.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        String fakes = "http://localhost:" + FAKES.getAddress().getPort();
        registry.add("spring.mongodb.uri", () -> MONGO.getReplicaSetUrl("gatekeeper_analytics"));
        registry.add("analytics.security.jwks-uri", () -> fakes + "/jwks.json");
        registry.add("analytics.prometheus.url", () -> fakes);
    }

    @LocalServerPort
    private int port;

    public record Response(int status, JsonNode body) {
    }

    protected Response get(String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(),
                    response.body().isBlank() ? null : JSON.readTree(response.body()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // A token as api-management would issue it; organizationId null = platform admin
    protected static String token(String role, Long organizationId) {
        return sign(SIGNING_KEY.getPrivate(), ISSUER, role, organizationId, Instant.now().plus(Duration.ofMinutes(10)));
    }

    protected static String sign(PrivateKey key, String issuer, String role, Long organizationId, Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("someone")
                .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(15))))
                .expirationTime(Date.from(expiresAt))
                .claim("uid", 1L)
                .claim("role", role);
        if (organizationId != null) {
            claims.claim("org_id", organizationId);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
