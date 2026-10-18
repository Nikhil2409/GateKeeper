package in.code.gatekeeper.analytic.kafka;

import in.code.gatekeeper.analytic.document.OrganizationDocument;
import in.code.gatekeeper.analytic.document.OrganizationDocument.RouteLimit;
import in.code.gatekeeper.analytic.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

/**
 * Keeps the latest configuration of every organization, read from the same compacted topic the gateways read
 * (with this service's own consumer group). A message replaces what is stored only if its version is newer, so
 * replays from the beginning of the topic and out-of-order deliveries are harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgConfigConsumer {

    private final OrganizationRepository organizationRepository;
    private final JsonMapper jsonMapper;

    // Tombstones (null values) and unreadable messages are skipped
    @KafkaListener(topics = "${analytics.kafka.org-config-topic}")
    public void onMessage(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return;
        }
        OrgConfigMessage message;
        try {
            message = jsonMapper.readValue(record.value(), OrgConfigMessage.class);
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Skipping unreadable configuration {}-{}@{}: {}", record.topic(), record.partition(),
                    record.offset(), e.getMessage());
            return;
        }
        consume(message);
    }

    public void consume(OrgConfigMessage message) {
        boolean stale = organizationRepository.findById(message.organizationId())
                .map(current -> message.configurationVersion() <= current.getConfigurationVersion())
                .orElse(false);
        if (stale) {
            return;
        }
        List<RouteLimit> routes = message.routes() == null ? List.of() : message.routes().stream()
                .map(route -> new RouteLimit(route.routeId(), route.requestsPerMinute(),
                        route.perUserRequestsPerMinute(), route.idempotencyEnabled()))
                .toList();
        organizationRepository.save(new OrganizationDocument(message.organizationId(), message.organizationSlug(),
                message.organizationName(), message.status(), message.configurationVersion(), routes, Instant.now()));
    }
}
