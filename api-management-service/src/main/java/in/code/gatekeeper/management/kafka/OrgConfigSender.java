package in.code.gatekeeper.management.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single place where organization configuration leaves this service: one compacted topic, keyed by
 * organizationId, so Kafka keeps the latest message per organization (decision #39).
 * Both publishers send through here. Values are plain JSON, so consumers depend on the shape, not on Java classes.
 *
 * Sending is asynchronous; a failure is logged and healed by the next startup publish, which republishes everything.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgConfigSender {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    @Value("${gatekeeper.gateway-config.topic}")
    private String topic;

    public void send(OrgConfigMessage message, String trigger) {
        kafkaTemplate.send(topic, String.valueOf(message.organizationId()), jsonMapper.writeValueAsString(message))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        log.error("Could not publish configuration of organization {} ({}) version {}: {}",
                                message.organizationId(), message.organizationSlug(), message.configurationVersion(),
                                error.getMessage());
                        return;
                    }
                    log.info("[{}] key={} ({}) version={} status={} routes={} tokenSettings={} trigger={} partition={} offset={}",
                            topic, message.organizationId(), message.organizationSlug(),
                            message.configurationVersion(), message.status(), message.routes().size(),
                            message.tokenSettings() != null, trigger, result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                });
    }

    public String topic() {
        return topic;
    }
}
