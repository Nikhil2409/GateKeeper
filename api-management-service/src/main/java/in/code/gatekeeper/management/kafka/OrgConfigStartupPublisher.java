package in.code.gatekeeper.management.kafka;

import in.code.gatekeeper.management.entity.ApiRoute;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import in.code.gatekeeper.management.repository.OrganizationTokenSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trigger 1 of 2: once, when this service has started, no API call involved. Publishes EVERY organization
 * (suspended and deleted ones too, with no routes), which seeds the topic for new gateways and repairs any change
 * whose send was lost while this service was down.
 */
@Slf4j
@Component
public class OrgConfigStartupPublisher {

    private final OrganizationRepository organizationRepository;
    private final ApiRouteRepository apiRouteRepository;
    private final OrganizationTokenSettingsRepository tokenSettingsRepository;
    private final OrgConfigSender sender;
    private final TransactionTemplate readOnlyTransaction;

    public OrgConfigStartupPublisher(OrganizationRepository organizationRepository,
                                     ApiRouteRepository apiRouteRepository,
                                     OrganizationTokenSettingsRepository tokenSettingsRepository,
                                     OrgConfigSender sender, PlatformTransactionManager transactionManager) {
        this.organizationRepository = organizationRepository;
        this.apiRouteRepository = apiRouteRepository;
        this.tokenSettingsRepository = tokenSettingsRepository;
        this.sender = sender;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void publishAll() {
        try {
            List<OrgConfigMessage> messages = readOnlyTransaction.execute(status -> buildAll());
            messages.forEach(message -> sender.send(message, "startup"));
            log.info("Published the configuration of all {} organizations to {}", messages.size(), sender.topic());
        } catch (RuntimeException e) {
            log.error("Could not publish the startup configuration to {}", sender.topic(), e);
        }
    }

    // Three queries in one transaction, so every message comes from the same consistent data
    private List<OrgConfigMessage> buildAll() {
        Map<Long, List<ApiRoute>> routesByOrganization = apiRouteRepository.findAllForGatewaySnapshot().stream()
                .collect(Collectors.groupingBy(route -> route.getOrganization().getId()));
        Map<Long, OrganizationTokenSettings> settingsByOrganization = tokenSettingsRepository.findAllByActiveTrue()
                .stream()
                .collect(Collectors.toMap(settings -> settings.getOrganization().getId(), Function.identity()));

        return organizationRepository.findAll(Sort.by("name")).stream()
                .map(organization -> OrgConfigMessage.from(organization,
                        settingsByOrganization.get(organization.getId()),
                        routesByOrganization.getOrDefault(organization.getId(), List.of())))
                .toList();
    }
}
