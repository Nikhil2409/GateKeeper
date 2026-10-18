package in.code.gatekeeper.gateway.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * The gateway owns gateway.traffic-events, so it declares it; Spring creates it on startup if it doesn't exist yet.
 * Kept 3 days: only a replay buffer for analytic-service, which keeps its own 15 days in MongoDB.
 */
@Configuration
public class TrafficEventsTopic {

    @Bean
    public NewTopic gatewayTrafficEventsTopic(@Value("${gateway.traffic-events.topic}") String topic,
                                              @Value("${gateway.kafka.partitions}") int partitions,
                                              @Value("${gateway.kafka.replication-factor}") int replicationFactor) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(3).toMillis()))
                .build();
    }
}
