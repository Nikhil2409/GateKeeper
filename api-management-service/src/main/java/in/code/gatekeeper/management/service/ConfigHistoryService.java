package in.code.gatekeeper.management.service;

import in.code.gatekeeper.management.dto.ConfigSnapshot;
import in.code.gatekeeper.management.dto.responseDto.ConfigPreviewResponse;
import in.code.gatekeeper.management.dto.responseDto.ConfigVersionResponse;
import in.code.gatekeeper.management.entity.Organization;
import in.code.gatekeeper.management.entity.OrganizationConfigVersion;
import in.code.gatekeeper.management.entity.OrganizationTokenSettings;
import in.code.gatekeeper.management.exception.ApiException;
import in.code.gatekeeper.management.kafka.OrganizationConfigChangedEvent;
import in.code.gatekeeper.management.repository.ApiRouteRepository;
import in.code.gatekeeper.management.repository.OrganizationConfigVersionRepository;
import in.code.gatekeeper.management.repository.OrganizationRepository;
import in.code.gatekeeper.management.repository.OrganizationTokenSettingsRepository;
import in.code.gatekeeper.management.util.ConfigDiff;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The rollback history: after every content change of an organization, its full configuration is stored as one
 * version, in the same transaction as the version bump, and only the newest max-versions are kept.
 */
@Service
@RequiredArgsConstructor
public class ConfigHistoryService {

    private final OrganizationConfigVersionRepository versionRepository;
    private final OrganizationRepository organizationRepository;
    private final ApiRouteRepository apiRouteRepository;
    private final OrganizationTokenSettingsRepository tokenSettingsRepository;
    private final EntityManager entityManager;
    private final JsonMapper jsonMapper;

    @Value("${gatekeeper.config-history.max-versions}")
    private int maxVersions;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void record(OrganizationConfigChangedEvent event) {
        if (!event.changeType().isContentChange()) {
            return;
        }
        entityManager.flush();
        Long organizationId = event.organizationId();
        Organization organization = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new IllegalStateException("Organization " + organizationId + " not found"));

        versionRepository.save(new OrganizationConfigVersion(organization, organization.getConfigurationVersion(),
                event.changeType(), event.changeDetail(), event.changedBy(), event.correlationId(),
                jsonMapper.writeValueAsString(currentSnapshot(organizationId))));
        prune(organizationId);
    }

    @Transactional(readOnly = true)
    public ConfigPreviewResponse preview(Long organizationId, long version) {
        List<OrganizationConfigVersion> versions = versionRepository.findAllByOrganizationIdOrderByVersionDesc(organizationId);
        OrganizationConfigVersion kept = versions.stream()
                .filter(candidate -> candidate.getVersion() == version)
                .findFirst()
                .orElseThrow(() -> notKept(version));
        ConfigSnapshot target = read(kept);
        return new ConfigPreviewResponse(version, versions.getFirst() == kept, target,
                ConfigDiff.between(currentSnapshot(organizationId), target));
    }

    // Newest first; the first one is the configuration in effect now
    @Transactional(readOnly = true)
    public List<ConfigVersionResponse> versions(Long organizationId) {
        List<OrganizationConfigVersion> versions = versionRepository.findAllByOrganizationIdOrderByVersionDesc(organizationId);
        return IntStream.range(0, versions.size())
                .mapToObj(index -> {
                    OrganizationConfigVersion version = versions.get(index);
                    ConfigSnapshot snapshot = read(version);
                    return new ConfigVersionResponse(version.getVersion(), version.getChangeType().name(),
                            version.getChangeDetail(), version.getChangedBy(), version.getCorrelationId(),
                            version.getDateCreated(), snapshot.activeRoutes(), snapshot.tokenSettings() != null,
                            index == 0);
                })
                .toList();
    }

    // The configuration of a kept version that is not the current one
    @Transactional(readOnly = true)
    public ConfigSnapshot snapshotToRestore(Long organizationId, long version) {
        List<OrganizationConfigVersion> versions = versionRepository.findAllByOrganizationIdOrderByVersionDesc(organizationId);
        if (!versions.isEmpty() && versions.getFirst().getVersion() == version) {
            throw ApiException.badRequest("Version " + version + " is already the current configuration");
        }
        return versions.stream()
                .filter(kept -> kept.getVersion() == version)
                .findFirst()
                .map(this::read)
                .orElseThrow(() -> notKept(version));
    }

    private ConfigSnapshot currentSnapshot(Long organizationId) {
        OrganizationTokenSettings tokenSettings = tokenSettingsRepository.findByOrganizationId(organizationId)
                .filter(OrganizationTokenSettings::isActive)
                .orElse(null);
        return ConfigSnapshot.from(apiRouteRepository.findAllByOrganizationIdOrderByRouteId(organizationId),
                tokenSettings);
    }

    private ApiException notKept(long version) {
        return ApiException.notFound("Version " + version + " is not available: only the last " + maxVersions
                + " versions are kept");
    }

    private void prune(Long organizationId) {
        List<OrganizationConfigVersion> versions = versionRepository.findAllByOrganizationIdOrderByVersionDesc(organizationId);
        if (versions.size() > maxVersions) {
            versionRepository.deleteAll(versions.subList(maxVersions, versions.size()));
        }
    }

    private ConfigSnapshot read(OrganizationConfigVersion version) {
        return jsonMapper.readValue(version.getSnapshot(), ConfigSnapshot.class);
    }
}
