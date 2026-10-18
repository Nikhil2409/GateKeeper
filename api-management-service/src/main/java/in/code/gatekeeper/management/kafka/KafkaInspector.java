package in.code.gatekeeper.management.kafka;

import in.code.gatekeeper.management.dto.responseDto.KafkaMessageResponse;
import in.code.gatekeeper.management.dto.responseDto.KafkaOverviewResponse;
import in.code.gatekeeper.management.dto.responseDto.KafkaOverviewResponse.Broker;
import in.code.gatekeeper.management.dto.responseDto.KafkaOverviewResponse.ConsumerGroup;
import in.code.gatekeeper.management.dto.responseDto.KafkaOverviewResponse.Topic;
import in.code.gatekeeper.management.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only look into Kafka for the platform admin: brokers, topics, consumer groups with their lag, and the latest
 * messages of a topic. Never writes or commits anything. Internal topics (__consumer_offsets, ...) are hidden.
 * Every call opens a short-lived client with a 3 s budget, so a Kafka outage shows as DOWN instead of hanging.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaInspector {

    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_VALUE_CHARS = 4000;

    private final KafkaAdmin kafkaAdmin;

    public KafkaOverviewResponse overview() {
        try (AdminClient admin = AdminClient.create(adminConfig())) {
            List<Broker> brokers = admin.describeCluster().nodes().get(TIMEOUT_MS, TimeUnit.MILLISECONDS).stream()
                    .map(node -> new Broker(node.id(), node.host(), node.port()))
                    .toList();

            Set<String> names = admin.listTopics().names().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Map<String, TopicDescription> descriptions = admin.describeTopics(names).allTopicNames()
                    .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            List<TopicPartition> partitions = descriptions.values().stream()
                    .flatMap(topic -> topic.partitions().stream().map(p -> new TopicPartition(topic.name(), p.partition())))
                    .toList();
            Map<TopicPartition, Long> latest = offsets(admin, partitions, OffsetSpec.latest());
            Map<TopicPartition, Long> earliest = offsets(admin, partitions, OffsetSpec.earliest());
            Map<String, Config> configs = topicConfigs(admin, names);

            List<Topic> topics = descriptions.values().stream()
                    .sorted(Comparator.comparing(TopicDescription::name))
                    .map(topic -> {
                        long messages = topic.partitions().stream()
                                .map(p -> new TopicPartition(topic.name(), p.partition()))
                                .mapToLong(tp -> latest.getOrDefault(tp, 0L) - earliest.getOrDefault(tp, 0L))
                                .sum();
                        Config config = configs.get(topic.name());
                        return new Topic(topic.name(), topic.partitions().size(), messages,
                                configValue(config, TopicConfig.CLEANUP_POLICY_CONFIG),
                                parseLong(configValue(config, TopicConfig.RETENTION_MS_CONFIG)));
                    })
                    .toList();

            return new KafkaOverviewResponse("UP", brokers, topics, consumerGroups(admin, latest));
        } catch (Exception e) {
            log.debug("Kafka overview unavailable: {}", e.toString());
            return new KafkaOverviewResponse("DOWN", List.of(), List.of(), List.of());
        }
    }

    // The newest messages of a topic across all its partitions, newest first
    public List<KafkaMessageResponse> latestMessages(String topic, int limit) {
        if (limit < 1 || limit > MAX_MESSAGES) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_MESSAGES);
        }
        if (topic.startsWith("__")) {
            throw ApiException.badRequest("Internal topics can't be browsed");
        }
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofMillis(TIMEOUT_MS)).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            if (partitions.isEmpty()) {
                throw ApiException.notFound("Topic " + topic + " not found");
            }
            consumer.assign(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofMillis(TIMEOUT_MS));
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions, Duration.ofMillis(TIMEOUT_MS));
            // the last `limit` offsets of every partition; the newest `limit` overall are picked from those
            partitions.forEach(tp -> consumer.seek(tp, Math.max(begin.get(tp), end.get(tp) - limit)));

            List<KafkaMessageResponse> messages = new ArrayList<>();
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline
                    && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    messages.add(toMessage(record));
                }
            }
            return messages.stream()
                    .sorted(Comparator.comparing(KafkaMessageResponse::timestamp).reversed())
                    .limit(limit)
                    .toList();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Could not read messages of {}: {}", topic, e.toString());
            throw ApiException.serviceUnavailable("Kafka is not reachable");
        }
    }

    private List<ConsumerGroup> consumerGroups(AdminClient admin, Map<TopicPartition, Long> latest) throws Exception {
        List<String> groupIds = admin.listGroups().all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS).stream()
                .map(GroupListing::groupId)
                .sorted()
                .toList();
        if (groupIds.isEmpty()) {
            return List.of();
        }
        Map<String, ConsumerGroupDescription> descriptions = admin.describeConsumerGroups(groupIds).all()
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

        List<ConsumerGroup> groups = new ArrayList<>();
        for (String groupId : groupIds) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Long lag = committed.isEmpty() ? null : committed.entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .mapToLong(entry -> Math.max(0, latest.getOrDefault(entry.getKey(), 0L) - entry.getValue().offset()))
                    .sum();
            List<String> topics = committed.keySet().stream().map(TopicPartition::topic).distinct().sorted().toList();
            ConsumerGroupDescription description = descriptions.get(groupId);
            groups.add(new ConsumerGroup(groupId,
                    description == null ? "UNKNOWN" : description.groupState().name(),
                    description == null ? 0 : description.members().size(), topics, lag));
        }
        return groups;
    }

    private static Map<TopicPartition, Long> offsets(AdminClient admin, List<TopicPartition> partitions,
                                                     OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> request = partitions.stream()
                .collect(Collectors.toMap(Function.identity(), tp -> spec));
        Map<TopicPartition, ListOffsetsResultInfo> result = admin.listOffsets(request).all()
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        Map<TopicPartition, Long> offsets = new HashMap<>();
        result.forEach((tp, info) -> offsets.put(tp, info.offset()));
        return offsets;
    }

    private static Map<String, Config> topicConfigs(AdminClient admin, Set<String> names) throws Exception {
        List<ConfigResource> resources = names.stream()
                .map(name -> new ConfigResource(ConfigResource.Type.TOPIC, name))
                .toList();
        Map<String, Config> configs = new HashMap<>();
        admin.describeConfigs(resources).all().get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .forEach((resource, config) -> configs.put(resource.name(), config));
        return configs;
    }

    private static String configValue(Config config, String name) {
        return config == null || config.get(name) == null ? null : config.get(name).value();
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static KafkaMessageResponse toMessage(ConsumerRecord<String, String> record) {
        String value = record.value();
        boolean truncated = value != null && value.length() > MAX_VALUE_CHARS;
        return new KafkaMessageResponse(record.partition(), record.offset(), Instant.ofEpochMilli(record.timestamp()),
                record.key(), truncated ? value.substring(0, MAX_VALUE_CHARS) : value, truncated);
    }

    private Map<String, Object> adminConfig() {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
        return config;
    }

    // No group id: assign() + seek only, nothing is ever committed, no consumer group appears in the overview
    private Map<String, Object> consumerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaAdmin.getConfigurationProperties().get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, TIMEOUT_MS);
        return config;
    }
}
