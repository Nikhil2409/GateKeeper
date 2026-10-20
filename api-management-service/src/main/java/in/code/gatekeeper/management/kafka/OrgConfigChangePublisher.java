package in.code.gatekeeper.management.kafka;

import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import in.code.gatekeeper.management.repository.OrganizationTokenSettingsRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Trigger 2 of 2: every committed change to an organization (routes, token settings, suspend/activate) publishes
 * that ONE organization's full configuration. Rolled-back changes are never announced.
 *
 * The message is built BEFORE commit, inside the change's own transaction and connection (the org row is still
 * locked, so it describes exactly the version this change commits), and sent only AFTER the commit succeeded.
 * Building it after commit would need a second connection per request and exhausts the pool under load (#43).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrgConfigChangePublisher {

    private final OrganizationRepository organizationRepository;
    private final ApiRouteRepository apiRouteRepository;
    private final OrganizationTokenSettingsRepository tokenSettingsRepository;
    private final EntityManager entityManager;
    private final OrgConfigSender sender;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrganizationConfigChanged(OrganizationConfigChangedEvent event) {
        OrgConfigMessage message = build(event.organizationId(), event.correlationId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // already committed: a failure here must not turn the caller's successful request into a 500
                try {
                    sender.send(message, "change");
                } catch (RuntimeException e) {
                    log.error("Could not publish configuration of organization {}", message.organizationId(), e);
                }
            }
        });
    }

    private OrgConfigMessage build(Long organizationId, String correlationId) {
        entityManager.flush();   // make this transaction's pending route/settings changes visible to the queries below
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalStateException("Organization " + organizationId + " not found"));
        List<ApiRoute> activeRoutes = apiRouteRepository.findAllByOrganizationIdOrderByRouteId(organizationId).stream()
                .filter(ApiRoute::isActive)
                .toList();
        OrganizationTokenSettings tokenSettings = tokenSettingsRepository.findByOrganizationId(organizationId)
                .filter(OrganizationTokenSettings::isActive)
                .orElse(null);
        return OrgConfigMessage.from(organization, tokenSettings, activeRoutes, correlationId);
    }
}
