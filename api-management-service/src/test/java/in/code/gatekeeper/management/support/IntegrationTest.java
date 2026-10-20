package in.code.gatekeeper.management.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base of every integration test: the real application on a random port, a real MySQL (schema by Flyway) and a
 * real Kafka, both in containers started once per test run and shared by all test classes (decision #74).
 * Tests talk to the service over HTTP (TestApi), so the real security chain runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0").withDatabaseName("gatekeeper_mgmt");
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");
    protected static final Path KEYS;

    static {
        MYSQL.start();
        KAFKA.start();
        try {
            KEYS = Files.createTempDirectory("gatekeeper-test-keys");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("gatekeeper.security.jwt.public-key", () -> "file:" + KEYS.resolve("public.pem"));
        registry.add("gatekeeper.security.jwt.private-key", () -> "file:" + KEYS.resolve("private.pem"));
    }

    @LocalServerPort
    private int port;

    protected TestApi api() {
        return new TestApi("http://localhost:" + port);
    }
}
