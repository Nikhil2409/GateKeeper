package in.code.gatekeeper.management.kafka;

/**
 * Raised in memory (Spring application event) by TenantService and PlatformService whenever an organization's
 * configuration version is bumped. OrgConfigChangePublisher turns it into a Kafka message once the change commits.
 */
public record OrganizationConfigChangedEvent(Long organizationId) {
}
