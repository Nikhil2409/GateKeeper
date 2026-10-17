-- Values come from environment variables through Flyway placeholders (see application.properties).
-- Passwords must be BCrypt hashes. NULLIF turns a missing hash into NULL, which the NOT NULL
-- password column rejects, so the migration fails loudly instead of creating an unusable account.

INSERT INTO management_users (organization_id, role_id, username, password, active, date_created, date_updated)
SELECT NULL, id, '${platform_admin_username}', NULLIF('${platform_admin_password_hash}', ''), TRUE, NOW(6), NOW(6)
FROM roles
WHERE name = 'PLATFORM_ADMIN';

INSERT INTO management_users (organization_id, role_id, username, password, active, date_created, date_updated)
SELECT NULL, id, '${gateway_client_username}', NULLIF('${gateway_client_password_hash}', ''), TRUE, NOW(6), NOW(6)
FROM roles
WHERE name = 'GATEWAY_SERVICE';
