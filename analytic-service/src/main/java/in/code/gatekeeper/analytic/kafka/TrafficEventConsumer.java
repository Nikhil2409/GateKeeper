package in.code.gatekeeper.analytic.kafka;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.repository.TrafficEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores every traffic event from the gateway. Saving by requestId makes redelivery harmless (Kafka delivers at
 * least once): the same event overwrites itself instead of being counted twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrafficEventConsumer {

    private final TrafficEventRepository trafficEventRepository;
    private final JsonMapper jsonMapper;

    // A message that can't be read is logged and skipped; a MongoDB failure is retried by the container
    @KafkaListener(topics = "${analytics.kafka.traffic-events-topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        TrafficEventMessage event;
        try {
            event = jsonMapper.readValue(record.value(), TrafficEventMessage.class);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Skipping unreadable traffic event {}-{}@{}: {}", record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            return;
        }
        MDC.put("correlationId", event.correlationId());
        try {
            consume(event);
        } finally {
            MDC.remove("correlationId");
        }
    }

    public void consume(TrafficEventMessage event) {
        trafficEventRepository.save(new TrafficEventDocument(event.requestId(), event.correlationId(),
                event.timestamp(), event.gatewayInstanceId(), event.organizationId(), event.organizationSlug(),
                event.routeId(),
                event.method(), event.path(), event.status(), event.latencyMs(), event.clientIp(),
                event.rejectReason(), event.idempotencyOutcome(), event.idempotencyKey()));
    }
}
