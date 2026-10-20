-- Configuration history for rollback: one row per content change of an organization (routes, token settings) with
-- its full configuration after that change, as JSON. Only the newest versions per organization are kept
-- (gatekeeper.config-history.max-versions); older rows are deleted in the same transaction.
CREATE TABLE organization_config_versions
(
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id BIGINT      NOT NULL,
    version         BIGINT      NOT NULL,
    change_type     VARCHAR(40) NOT NULL,
    change_detail   VARCHAR(200),
    changed_by      VARCHAR(50),
    correlation_id  VARCHAR(64),
    snapshot        LONGTEXT    NOT NULL,
    date_created    DATETIME(6) NOT NULL,
    CONSTRAINT pk_organization_config_versions PRIMARY KEY (id),
    CONSTRAINT uk_organization_config_versions_version UNIQUE (organization_id, version),
    CONSTRAINT fk_organization_config_versions_org FOREIGN KEY (organization_id) REFERENCES organizations (id)
);
