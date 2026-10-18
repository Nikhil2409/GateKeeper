package in.code.gatekeeper.management.service;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class HealthService {

    private static final int KAFKA_TIMEOUT_MS = 2000;
    // /health is public: the Kafka check runs at most once per interval, however often /health is called
    private static final Duration KAFKA_CHECK_INTERVAL = Duration.ofSeconds(10);

    public final JdbcTemplate jdbcTemplate;
    private final KafkaAdmin kafkaAdmin;

    private volatile String kafkaStatus;
    private volatile Instant kafkaCheckedAt = Instant.EPOCH;

    public String getDBStatus() {
     String databaseStatus = "DOWN";

     try{
         Integer result = jdbcTemplate.queryForObject("Select 1", Integer.class);
         if(Integer.valueOf(1).equals(result)){
             databaseStatus = "UP";
         }
     }catch(Exception e){
         System.out.print("DB is not up");
        }
     return databaseStatus;
    }

    // UP when the Kafka cluster answers within the timeout (the gateway configuration is published there)
    public String getKafkaStatus() {
        if (kafkaStatus == null || Instant.now().isAfter(kafkaCheckedAt.plus(KAFKA_CHECK_INTERVAL))) {
            kafkaStatus = checkKafka();
            kafkaCheckedAt = Instant.now();
        }
        return kafkaStatus;
    }

    private String checkKafka() {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, KAFKA_TIMEOUT_MS);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, KAFKA_TIMEOUT_MS);
        try (AdminClient admin = AdminClient.create(config)) {
            admin.describeCluster(new DescribeClusterOptions().timeoutMs(KAFKA_TIMEOUT_MS))
                    .nodes().get(KAFKA_TIMEOUT_MS + 500, TimeUnit.MILLISECONDS);
            return "UP";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}
