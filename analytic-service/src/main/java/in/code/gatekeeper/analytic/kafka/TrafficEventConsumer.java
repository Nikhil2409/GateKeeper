package in.code.gatekeeper.analytic.kafka;

import in.code.gatekeeper.analytic.document.TrafficEventDocument;
import in.code.gatekeeper.analytic.repository.TrafficEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Stores every traffic event from the gateway. Saving by requestId makes redelivery harmless (Kafka delivers at
 * least once): the same event overwrites itself instead of being counted twice.
 */
@Component
@RequiredArgsConstructor
public class TrafficEventConsumer {

    private final TrafficEventRepository trafficEventRepository;

    // Kafka integration point: @KafkaListener(topics = "${analytics.kafka.traffic-events-topic}")
    public void consume(TrafficEventMessage event) {
        trafficEventRepository.save(new TrafficEventDocument(event.requestId(), event.timestamp(),
                event.gatewayInstanceId(), event.organizationId(), event.organizationSlug(), event.routeId(),
                event.method(), event.path(), event.status(), event.latencyMs(), event.clientIp(),
                event.rejectReason(), event.idempotencyOutcome(), event.idempotencyKey()));
    }
}
