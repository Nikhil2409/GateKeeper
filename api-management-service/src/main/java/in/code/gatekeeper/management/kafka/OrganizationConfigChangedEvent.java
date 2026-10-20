package in.code.gatekeeper.management.kafka;

import in.code.gatekeeper.management.enums.ConfigChangeType;

/**
 * Raised in memory (Spring application event) by TenantService and PlatformService whenever an organization's
 * configuration version is bumped. Before the change commits, OrgConfigChangePublisher builds the Kafka message
 * and ConfigHistoryService records the version for rollback.
 */
public record OrganizationConfigChangedEvent(Long organizationId, ConfigChangeType changeType, String changeDetail,
                                             String changedBy, String correlationId) {
}
