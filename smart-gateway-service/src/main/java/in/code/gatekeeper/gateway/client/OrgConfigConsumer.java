package in.code.gatekeeper.gateway.client;

import in.code.gatekeeper.gateway.filter.TrafficEventFilter;
import in.code.gatekeeper.gateway.model.OrganizationConfig;
import in.code.gatekeeper.gateway.registry.RouteRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the RouteRegistry from gateway.org-config, the compacted topic api-management publishes (decision #39).
 *
 * The registry lives in memory, so EVERY start reads the whole topic from the beginning: this consumer assigns itself
 * all partitions (no group membership, so every instance gets every message) and seeks to the start. It notes where
 * the topic ended when it connected; once it has read that far the configuration is complete and the registry is
 * marked ready (until then tenant requests get 503). It then keeps consuming changes as they are published.
 *
 * Kafka unreachable: it retries every few seconds. If the gateway was already ready it keeps serving what it has,
 * and reading the topic again on reconnect is harmless (the version rule ignores what it already has).
 * The group id only makes its progress visible as a consumer group (lag) in the platform admin's Kafka view.
 */
@Slf4j
@Component
public class OrgConfigConsumer implements SmartLifecycle {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(5);
    private static final long RETRY_DELAY_MS = 5000;

    private final RouteRegistry routeRegistry;
    private final JsonMapper jsonMapper;
    private final String topic;
    private final Map<String, Object> config = new HashMap<>();

    private volatile boolean running;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile String state = "Starting";
    private Thread thread;

    public OrgConfigConsumer(RouteRegistry routeRegistry, JsonMapper jsonMapper,
                             @Value("${gateway.org-config.topic}") String topic,
                             @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                             @Value("${gateway.instance-id}") String instanceId) {
        this.routeRegistry = routeRegistry;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "smart-gateway-" + instanceId);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);   // progress only; every start seeks to the beginning
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) KAFKA_TIMEOUT.toMillis());
    }

    // For /_gateway/health while not ready, e.g. "Kafka is not reachable"
    public String state() {
        return state;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofVirtual().name("org-config-consumer").start(this::run);
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<String, String> current = consumer;
        if (current != null) {
            current.wakeup();   // interrupts a blocking poll; the consumer is closed by its own thread
        }
        try {
            if (thread != null) {
                thread.join(KAFKA_TIMEOUT.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            try (KafkaConsumer<String, String> kafka = new KafkaConsumer<>(config)) {
                consumer = kafka;
                consumeFromBeginning(kafka);
            } catch (WakeupException e) {
                if (!running) {
                    return;
                }
            } catch (TopicMissingException e) {
                state = e.getMessage();
                log.warn("{}, retrying in {} s", e.getMessage(), RETRY_DELAY_MS / 1000);
                pause();
            } catch (Exception e) {
                state = "Kafka is not reachable";
                log.warn("Reading {} failed, retrying in {} s: {}", topic, RETRY_DELAY_MS / 1000, e.toString());
                pause();
            } finally {
                consumer = null;
            }
        }
    }

    private void consumeFromBeginning(KafkaConsumer<String, String> kafka) {
        state = "Connecting to Kafka";
        List<TopicPartition> partitions = kafka.partitionsFor(topic, KAFKA_TIMEOUT).stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
        if (partitions.isEmpty()) {
            throw new TopicMissingException("Waiting for topic " + topic + " (api-management creates it on startup)");
        }
        kafka.assign(partitions);
        kafka.seekToBeginning(partitions);
        Map<TopicPartition, Long> endAtStart = kafka.endOffsets(partitions, KAFKA_TIMEOUT);
        state = "Loading configuration from Kafka";
        log.info("Reading {} from the beginning ({} partitions, {} messages)", topic, partitions.size(),
                endAtStart.values().stream().mapToLong(Long::longValue).sum());

        while (running) {
            for (ConsumerRecord<String, String> record : kafka.poll(POLL_TIMEOUT)) {
                apply(record);
            }
            if (!routeRegistry.isReady() && partitions.stream().allMatch(tp -> kafka.position(tp) >= endAtStart.get(tp))) {
                routeRegistry.markReady();
                log.info("Configuration loaded: {} organizations, gateway is ready", routeRegistry.all().size());
            }
            if (routeRegistry.isReady()) {
                state = "Up to date";
            }
        }
    }

    // A message that can't be read is logged and skipped, so it can never block the topic
    private void apply(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return;
        }
        try {
            OrganizationConfig config = jsonMapper.readValue(record.value(), OrganizationConfig.class);
            MDC.put(TrafficEventFilter.MDC_CORRELATION_ID, config.correlationId());
            if (routeRegistry.apply(config)) {
                log.info("Applied configuration of {} version {} ({}, {} routes)", config.organizationSlug(),
                        config.configurationVersion(), config.status(), config.routes().size());
            }
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Skipping unreadable message {}-{}@{}: {}", record.topic(), record.partition(), record.offset(),
                    e.getMessage());
        } finally {
            MDC.remove(TrafficEventFilter.MDC_CORRELATION_ID);
        }
    }

    // Kafka answers, but the topic isn't there yet: a different health message than "not reachable"
    private static class TopicMissingException extends RuntimeException {
        TopicMissingException(String message) {
            super(message);
        }
    }

    private void pause() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
