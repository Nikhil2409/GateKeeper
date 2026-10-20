package in.code.gatekeeper.management.support;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads what api-management published to gateway.org-config, the way a gateway does: every partition from the
 * beginning up to the current end. Sends happen right after commit, so callers retry until the message they expect
 * has arrived (latest()).
 */
public final class PublishedConfigs {

    private static final String TOPIC = "gateway.org-config";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PublishedConfigs() {
    }

    // Every message for the organization, in the order it was published
    public static List<JsonNode> all(String bootstrapServers, long organizationId) {
        Map<String, Object> config = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<JsonNode> messages = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (record.key().equals(String.valueOf(organizationId)) && record.value() != null) {
                        messages.add(JSON.readTree(record.value()));
                    }
                }
            }
        }
        return messages;
    }

    // The newest message once it carries at least this version (waits up to 10 s)
    public static JsonNode latest(String bootstrapServers, long organizationId, long minVersion) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            List<JsonNode> messages = all(bootstrapServers, organizationId);
            if (!messages.isEmpty() && messages.getLast().get("configurationVersion").asLong() >= minVersion) {
                return messages.getLast();
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no message with version >= " + minVersion + " for organization "
                        + organizationId + "; got " + messages.size() + " messages");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
