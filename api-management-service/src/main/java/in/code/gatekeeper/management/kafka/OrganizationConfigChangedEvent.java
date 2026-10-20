package in.code.gatekeeper.management.kafka;

/**
 * Raised in memory (Spring application event) by TenantService and PlatformService whenever an organization's
 * configuration version is bumped. OrgConfigChangePublisher turns it into a Kafka message once the change commits.
 * correlationId: the id of the admin request that made the change, carried into the message.
 */
public record OrganizationConfigChangedEvent(Long organizationId, String correlationId) {
}
