package in.code.gatekeeper.gateway.client;

import in.code.gatekeeper.gateway.model.TrafficEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where traffic events leave the gateway: topic gateway.traffic-events, keyed by organizationId so one
 * organization's events stay in order. Values are plain JSON.
 *
 * Must never slow down or fail a request: publish() only puts the event in a bounded in-memory queue; one sender
 * thread hands it to Kafka. When Kafka is down long enough for the queue to fill up, new events are DROPPED (and
 * counted in the log): analytics loses some data, tenant traffic keeps flowing.
 */
@Slf4j
@Component
public class TrafficEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;
    private final String topic;
    private final ThreadPoolExecutor sender;
    private final AtomicLong dropped = new AtomicLong();

    public TrafficEventPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper,
                                 @Value("${gateway.traffic-events.topic}") String topic,
                                 @Value("${gateway.traffic-events.queue-size}") int queueSize) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
        this.topic = topic;
        this.sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueSize),
                Thread.ofVirtual().name("traffic-events-sender").factory(), (task, executor) -> drop("queue full"));
    }

    public void publish(TrafficEvent event) {
        sender.execute(() -> send(event));
    }

    private void send(TrafficEvent event) {
        try {
            String key = event.organizationId() == null ? null : String.valueOf(event.organizationId());
            kafkaTemplate.send(topic, key, jsonMapper.writeValueAsString(event))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            drop(error.getMessage());
                        }
                    });
        } catch (RuntimeException e) {
            drop(e.getMessage());
        }
    }

    // Logged on the first drop and then every 1000th, so an outage doesn't flood the log
    private void drop(String reason) {
        long count = dropped.incrementAndGet();
        if (count == 1 || count % 1000 == 0) {
            log.warn("Dropped {} traffic events so far ({})", count, reason);
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        sender.shutdown();
        sender.awaitTermination(2, TimeUnit.SECONDS);
    }
}
