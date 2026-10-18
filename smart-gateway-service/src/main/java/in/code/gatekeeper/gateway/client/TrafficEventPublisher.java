package in.code.gatekeeper.gateway.client;

import in.code.gatekeeper.gateway.model.TrafficEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where traffic events leave the gateway: topic gateway.traffic-events, keyed by organizationId so one
 * organization's events stay in order. Must never slow down or fail a request; until the Kafka step it only logs.
 */
@Slf4j
@Component
public class TrafficEventPublisher {

    @Value("${gateway.traffic-events.topic}")
    private String topic;

    // Kafka integration point: kafkaTemplate.send(topic, String.valueOf(event.organizationId()), event) (async)
    public void publish(TrafficEvent event) {
        log.info("[{}] {} {} {} -> {} in {} ms org={} route={} reject={} idempotency={}", topic, event.requestId(),
                event.method(), event.path(), event.status(), event.latencyMs(), event.organizationSlug(),
                event.routeId(), event.rejectReason(), event.idempotencyOutcome());
    }
}
