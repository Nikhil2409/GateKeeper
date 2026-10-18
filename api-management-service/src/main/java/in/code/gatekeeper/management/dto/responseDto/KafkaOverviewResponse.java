package in.code.gatekeeper.management.dto.responseDto;

import java.util.List;

/**
 * What the platform admin's Kafka view shows. status UP/DOWN; when DOWN the lists are empty.
 * messages: offsets currently in the topic (for a compacted topic this counts compacted gaps too, so it is an
 * upper bound). lag: messages a consumer group has not read yet (null when it never committed an offset).
 */
public record KafkaOverviewResponse(String status, List<Broker> brokers, List<Topic> topics,
                                    List<ConsumerGroup> consumerGroups) {

    public record Broker(int id, String host, int port) {
    }

    public record Topic(String name, int partitions, long messages, String cleanupPolicy, Long retentionMs) {
    }

    public record ConsumerGroup(String groupId, String state, int members, List<String> topics, Long lag) {
    }
}
