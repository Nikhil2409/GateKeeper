-- One configuration version per organization, bumped on every change to its routes or status.
-- The gateway tracks a single version per organization instead of one per route.
ALTER TABLE organizations ADD COLUMN configuration_version BIGINT NOT NULL DEFAULT 0 AFTER status;
ALTER TABLE api_routes DROP COLUMN configuration_version;

-- Audit and change history live in the analytic service (fed by the gateway over Kafka).
DROP TABLE route_configuration_versions;
DROP TABLE control_plane_audit_records;
