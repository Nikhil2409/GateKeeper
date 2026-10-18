package in.code.gatekeeper.management.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * This service owns gateway.org-config, so it declares it; Spring creates it on startup if it doesn't exist yet
 * (the broker never creates topics by itself). Compacted: Kafka keeps the latest message per organization forever,
 * which is what lets a gateway rebuild its whole configuration by reading the topic from the beginning.
 */
@Configuration
public class OrgConfigTopic {

    @Bean
    public NewTopic gatewayOrgConfigTopic(@Value("${gatekeeper.gateway-config.topic}") String topic,
                                          @Value("${gatekeeper.kafka.partitions}") int partitions,
                                          @Value("${gatekeeper.kafka.replication-factor}") int replicationFactor) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .compact()
                .build();
    }
}
