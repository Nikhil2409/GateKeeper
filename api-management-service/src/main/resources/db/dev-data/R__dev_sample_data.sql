-- Sample data for LOCAL DEVELOPMENT ONLY. Flyway reads this folder only with the "dev" profile
-- (application-dev.properties, activated by start.ps1); other environments never see it.
--
-- 6 organizations (Wayne Logistics suspended, Initech without token settings), one tenant admin each,
-- 8-10 routes per organization with methods, allowed headers and a few header rules.
-- Tenant admins: <slug>-admin / demo-pass-123   (e.g. acme-admin)
--
-- Repeatable migration: Flyway runs it again only when this file changes. Every insert skips what already exists
-- (by slug / username / routeId), so it never duplicates data or overwrites changes made in the UI.
-- Route targets are made-up internal hosts: calls through the gateway reach the checks, then answer 502.

CREATE TEMPORARY TABLE seed_organizations
(
    slug           VARCHAR(100),
    name           VARCHAR(150),
    status         VARCHAR(20),
    admin_username VARCHAR(50),
    issuer         VARCHAR(500),
    jwks_uri       VARCHAR(1000),
    audience       VARCHAR(200)
);

INSERT INTO seed_organizations
VALUES ('acme', 'Acme Corporation', 'ACTIVE', 'acme-admin',
        'https://acme.eu.auth0.com/', 'https://acme.eu.auth0.com/.well-known/jwks.json', 'acme-api'),
       ('globex', 'Globex', 'ACTIVE', 'globex-admin',
        'https://globex.okta.com/oauth2/default', 'https://globex.okta.com/oauth2/default/v1/keys', 'api://globex'),
       ('initech', 'Initech', 'ACTIVE', 'initech-admin', NULL, NULL, NULL),
       ('umbrella', 'Umbrella Health', 'ACTIVE', 'umbrella-admin',
        'https://login.umbrella-health.example/realms/patients',
        'https://login.umbrella-health.example/realms/patients/protocol/openid-connect/certs', 'umbrella-api'),
       ('stark', 'Stark Industries', 'ACTIVE', 'stark-admin',
        'https://auth.stark.example/', 'https://auth.stark.example/.well-known/jwks.json', 'stark-devices'),
       ('wayne', 'Wayne Logistics', 'SUSPENDED', 'wayne-admin',
        'https://id.wayne-logistics.example/', 'https://id.wayne-logistics.example/.well-known/jwks.json', 'wayne-api');

INSERT INTO organizations (name, slug, status, configuration_version, active, date_created, date_updated)
SELECT s.name, s.slug, s.status, 0, TRUE, NOW(6), NOW(6)
FROM seed_organizations s
WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.slug = s.slug OR o.name = s.name);

-- one ACTIVE tenant admin per organization: skipped when the organization already has one
INSERT INTO management_users (organization_id, role_id, username, password, active, date_created, date_updated)
SELECT o.id, r.id, s.admin_username, '$2a$10$9tTmRE0Bbc9BECuQtAAk4uZmiedUoLC/5PwPABKJwLrDipNL75puW', TRUE, NOW(6), NOW(6)
FROM seed_organizations s
         JOIN organizations o ON o.slug = s.slug
         JOIN roles r ON r.name = 'TENANT_ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM management_users u WHERE u.username = s.admin_username)
  AND NOT EXISTS (SELECT 1 FROM management_users u WHERE u.organization_id = o.id AND u.active);

INSERT INTO organization_token_settings (organization_id, issuer, jwks_uri, audience, active, date_created, date_updated)
SELECT o.id, s.issuer, s.jwks_uri, s.audience, TRUE, NOW(6), NOW(6)
FROM seed_organizations s
         JOIN organizations o ON o.slug = s.slug
WHERE s.issuer IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM organization_token_settings t WHERE t.organization_id = o.id);

-- ---------- routes ----------
-- methods / headers: JSON arrays. Headers are lowercase; Content-Type and Accept are always forwarded anyway,
-- X-Correlation-Id / X-Request-Id are set by the gateway and can't be allowlisted.
CREATE TEMPORARY TABLE seed_routes
(
    slug                VARCHAR(100),
    route_id            VARCHAR(100),
    name                VARCHAR(200),
    path_pattern        VARCHAR(500),
    target_base_url     VARCHAR(1000),
    target_path_prefix  VARCHAR(500),
    methods             JSON,
    headers             JSON,
    requests_per_minute INTEGER,
    response_timeout_ms INTEGER,
    idempotency_enabled BOOLEAN
);

INSERT INTO seed_routes
VALUES
    -- Acme Corporation: online shop
    ('acme', 'orders', 'List or place orders', '/orders', 'http://orders.acme.internal:8080', '/api/v1',
     '["GET", "POST"]', '["authorization", "idempotency-key", "accept-language"]', 600, 5000, TRUE),
    ('acme', 'customer', 'Customer profile', '/customers/{customerId}', 'http://customers.acme.internal:8080', '',
     '["GET", "PATCH"]', '["authorization", "if-match"]', 300, 3000, FALSE),
    ('acme', 'order', 'Get, update or cancel an order', '/orders/{orderId}', 'http://orders.acme.internal:8080', '/api/v1',
     '["GET", "PATCH", "DELETE"]', '["authorization", "if-match"]', 600, 3000, FALSE),
    ('acme', 'order-items', 'Order items', '/orders/{orderId}/items', 'http://orders.acme.internal:8080', '/api/v1',
     '["GET"]', '["authorization"]', 600, 3000, FALSE),
    ('acme', 'create-payment', 'Create payment', '/payments', 'http://payments.acme.internal:8443', '/v2',
     '["POST"]', '["authorization", "idempotency-key"]', 120, 10000, TRUE),
    ('acme', 'payment', 'Payment details', '/payments/{paymentId}', 'http://payments.acme.internal:8443', '/v2',
     '["GET"]', '["authorization"]', 300, 5000, FALSE),
    ('acme', 'refund', 'Refund a payment', '/payments/{paymentId}/refunds', 'http://payments.acme.internal:8443', '/v2',
     '["POST"]', '["authorization", "idempotency-key"]', 60, 10000, TRUE),
    ('acme', 'inventory', 'Inventory', '/inventory/items', 'http://inventory.acme.internal:8080', '',
     '["GET", "HEAD"]', '["authorization", "if-none-match"]', 1200, 2000, FALSE),
    ('acme', 'search-products', 'Search products', '/products/search', 'http://search.acme.internal:9200', '',
     '["GET"]', '["authorization", "accept-language"]', NULL, 2000, FALSE),

    -- Globex: marketplace
    ('globex', 'catalog', 'Product catalog', '/catalog/products', 'http://catalog.globex.internal:8080', '/api',
     '["GET"]', '["authorization", "accept-language", "x-client-version"]', 900, 2000, FALSE),
    ('globex', 'product', 'Product details', '/catalog/products/{productId}', 'http://catalog.globex.internal:8080', '/api',
     '["GET"]', '["authorization", "accept-language", "if-none-match"]', 900, 2000, FALSE),
    ('globex', 'product-reviews', 'Product reviews', '/catalog/products/{productId}/reviews', 'http://reviews.globex.internal:8080', '',
     '["GET", "POST"]', '["authorization", "accept-language"]', 200, 3000, FALSE),
    ('globex', 'cart', 'Shopping cart', '/cart', 'http://cart.globex.internal:8080', '',
     '["GET", "PUT", "DELETE"]', '["authorization", "x-session-id"]', 600, 2000, FALSE),
    ('globex', 'checkout', 'Checkout', '/checkout', 'http://checkout.globex.internal:8080', '',
     '["POST"]', '["authorization", "idempotency-key", "x-session-id"]', 200, 8000, TRUE),
    ('globex', 'my-account', 'My account', '/accounts/me', 'http://accounts.globex.internal:8080', '',
     '["GET", "PATCH"]', '["authorization"]', 300, 3000, FALSE),
    ('globex', 'my-addresses', 'My addresses', '/accounts/me/addresses', 'http://accounts.globex.internal:8080', '',
     '["GET", "POST"]', '["authorization"]', 120, 3000, FALSE),
    ('globex', 'my-address', 'Update or remove an address', '/accounts/me/addresses/{addressId}', 'http://accounts.globex.internal:8080', '',
     '["PUT", "DELETE"]', '["authorization", "if-match"]', 120, 3000, FALSE),

    -- Initech: internal tools (no token settings yet: every call answers 401)
    ('initech', 'reports', 'List reports', '/reports', 'http://reports.initech.internal:8080', '',
     '["GET"]', '["authorization", "x-report-format"]', 30, 15000, FALSE),
    ('initech', 'report', 'Report details', '/reports/{reportId}', 'http://reports.initech.internal:8080', '',
     '["GET"]', '["authorization", "x-report-format"]', 60, 15000, FALSE),
    ('initech', 'create-report', 'Generate a report', '/reports/generate', 'http://reports.initech.internal:8080', '',
     '["POST"]', '["authorization", "idempotency-key"]', 10, 30000, TRUE),
    ('initech', 'tps-cover-sheets', 'TPS cover sheets', '/tps/cover-sheets', 'http://tps.initech.internal:8080', '',
     '["GET", "POST"]', '["authorization"]', 60, 5000, FALSE),
    ('initech', 'tps-cover-sheet', 'TPS cover sheet', '/tps/cover-sheets/{sheetId}', 'http://tps.initech.internal:8080', '',
     '["GET", "PUT"]', '["authorization", "if-match"]', 60, 5000, FALSE),
    ('initech', 'employees', 'Employees', '/employees', 'http://hr.initech.internal:8080', '/v1',
     '["GET"]', '["authorization"]', 120, 3000, FALSE),
    ('initech', 'employee', 'Employee', '/employees/{employeeId}', 'http://hr.initech.internal:8080', '/v1',
     '["GET", "PATCH"]', '["authorization"]', 120, 3000, FALSE),
    ('initech', 'staplers', 'Stapler inventory', '/inventory/staplers', 'http://facilities.initech.internal:8080', '',
     '["GET"]', '["authorization"]', NULL, 2000, FALSE),

    -- Umbrella Health: patient portal
    ('umbrella', 'patients', 'Find or register patients', '/patients', 'http://patients.umbrella.internal:8080', '/fhir',
     '["GET", "POST"]', '["authorization", "accept-language", "idempotency-key"]', 300, 5000, TRUE),
    ('umbrella', 'patient', 'Patient record', '/patients/{patientId}', 'http://patients.umbrella.internal:8080', '/fhir',
     '["GET", "PUT"]', '["authorization", "if-match"]', 300, 5000, FALSE),
    ('umbrella', 'lab-results', 'Lab results', '/patients/{patientId}/lab-results', 'http://labs.umbrella.internal:8080', '',
     '["GET"]', '["authorization", "if-none-match"]', 120, 8000, FALSE),
    ('umbrella', 'appointments', 'Book or list appointments', '/appointments', 'http://scheduling.umbrella.internal:8080', '',
     '["GET", "POST"]', '["authorization", "idempotency-key", "accept-language"]', 200, 4000, TRUE),
    ('umbrella', 'appointment', 'Appointment', '/appointments/{appointmentId}', 'http://scheduling.umbrella.internal:8080', '',
     '["GET", "PATCH", "DELETE"]', '["authorization", "if-match"]', 200, 4000, FALSE),
    ('umbrella', 'availability', 'Practitioner availability', '/practitioners/{practitionerId}/availability', 'http://scheduling.umbrella.internal:8080', '',
     '["GET"]', '["authorization", "accept-language"]', 600, 3000, FALSE),
    ('umbrella', 'prescriptions', 'Issue a prescription', '/prescriptions', 'http://pharmacy.umbrella.internal:8443', '/api',
     '["POST"]', '["authorization", "idempotency-key"]', 60, 10000, TRUE),
    ('umbrella', 'prescription', 'Prescription', '/prescriptions/{prescriptionId}', 'http://pharmacy.umbrella.internal:8443', '/api',
     '["GET"]', '["authorization"]', 120, 5000, FALSE),
    ('umbrella', 'status', 'Service status', '/status', 'http://status.umbrella.internal:8080', '',
     '["GET", "HEAD"]', '[]', NULL, 1000, FALSE),

    -- Stark Industries: connected devices
    ('stark', 'devices', 'List or register devices', '/devices', 'http://devices.stark.internal:8080', '/api/v3',
     '["GET", "POST"]', '["authorization"]', 600, 3000, FALSE),
    ('stark', 'device', 'Device', '/devices/{deviceId}', 'http://devices.stark.internal:8080', '/api/v3',
     '["GET", "PATCH", "DELETE"]', '["authorization", "if-match"]', 600, 3000, FALSE),
    ('stark', 'telemetry', 'Send telemetry', '/devices/{deviceId}/telemetry', 'http://telemetry.stark.internal:9000', '',
     '["POST"]', '["authorization", "content-encoding"]', 6000, 1000, FALSE),
    ('stark', 'commands', 'Send a command', '/devices/{deviceId}/commands', 'http://devices.stark.internal:8080', '/api/v3',
     '["POST"]', '["authorization", "idempotency-key"]', 120, 5000, TRUE),
    ('stark', 'firmware', 'Firmware releases', '/firmware/releases', 'http://firmware.stark.internal:8080', '',
     '["GET"]', '["authorization", "if-none-match"]', 300, 3000, FALSE),
    ('stark', 'firmware-release', 'Firmware release', '/firmware/releases/{releaseId}', 'http://firmware.stark.internal:8080', '',
     '["GET"]', '["authorization", "if-none-match"]', 300, 3000, FALSE),
    ('stark', 'alerts', 'Alerts', '/alerts', 'http://alerts.stark.internal:8080', '',
     '["GET"]', '["authorization", "accept-language"]', 300, 3000, FALSE),
    ('stark', 'acknowledge-alert', 'Acknowledge an alert', '/alerts/{alertId}/acknowledge', 'http://alerts.stark.internal:8080', '',
     '["POST"]', '["authorization", "idempotency-key"]', 120, 3000, TRUE),
    ('stark', 'mark-85', 'Mark 85 diagnostics', '/suits/mark-85', 'http://suits.stark.internal:8080', '',
     '["GET"]', '["authorization"]', 10, 2000, FALSE),
    ('stark', 'suit-status', 'Suit status', '/suits/{suitId}/status', 'http://suits.stark.internal:8080', '',
     '["GET"]', '["authorization"]', 60, 2000, FALSE),

    -- Wayne Logistics: shipping (organization suspended: the gateway serves none of these)
    ('wayne', 'shipments', 'Create or list shipments', '/shipments', 'http://shipments.wayne.internal:8080', '',
     '["GET", "POST"]', '["authorization", "idempotency-key"]', 300, 5000, TRUE),
    ('wayne', 'shipment', 'Shipment', '/shipments/{shipmentId}', 'http://shipments.wayne.internal:8080', '',
     '["GET", "PATCH"]', '["authorization", "if-match"]', 300, 5000, FALSE),
    ('wayne', 'tracking', 'Tracking', '/shipments/{shipmentId}/tracking', 'http://tracking.wayne.internal:8080', '',
     '["GET"]', '["authorization"]', 900, 2000, FALSE),
    ('wayne', 'labels', 'Print a label', '/shipments/{shipmentId}/labels', 'http://labels.wayne.internal:8080', '',
     '["POST"]', '["authorization", "idempotency-key"]', 60, 10000, TRUE),
    ('wayne', 'warehouses', 'Warehouses', '/warehouses', 'http://warehouses.wayne.internal:8080', '',
     '["GET"]', '["authorization"]', 120, 3000, FALSE),
    ('wayne', 'warehouse-stock', 'Warehouse stock', '/warehouses/{warehouseId}/stock', 'http://warehouses.wayne.internal:8080', '',
     '["GET"]', '["authorization", "if-none-match"]', 300, 3000, FALSE),
    ('wayne', 'rate-quote', 'Quote shipping rates', '/rates/quote', 'http://rates.wayne.internal:8080', '',
     '["POST"]', '["authorization", "x-client-version"]', 600, 2000, FALSE),
    ('wayne', 'pickups', 'Schedule a pickup', '/pickups', 'http://pickups.wayne.internal:8080', '',
     '["POST"]', '["authorization", "idempotency-key"]', 60, 5000, TRUE);

-- routes this run creates are the ones above this id: only those get methods, headers and rules
SET @routes_before = (SELECT COALESCE(MAX(id), 0) FROM api_routes);

INSERT INTO api_routes (organization_id, name, route_id, path_pattern, target_base_url, target_path_prefix,
                        requests_per_minute, response_timeout_ms, idempotency_enabled, active, date_created, date_updated)
SELECT o.id, s.name, s.route_id, s.path_pattern, s.target_base_url, s.target_path_prefix,
       s.requests_per_minute, s.response_timeout_ms, s.idempotency_enabled, TRUE, NOW(6), NOW(6)
FROM seed_routes s
         JOIN organizations o ON o.slug = s.slug
WHERE NOT EXISTS (SELECT 1 FROM api_routes r
                  WHERE r.organization_id = o.id AND (r.route_id = s.route_id OR r.path_pattern = s.path_pattern));

INSERT INTO route_allowed_methods (route_pk, http_method)
SELECT r.id, m.method
FROM seed_routes s
         JOIN organizations o ON o.slug = s.slug
         JOIN api_routes r ON r.organization_id = o.id AND r.route_id = s.route_id AND r.id > @routes_before,
     JSON_TABLE(s.methods, '$[*]' COLUMNS (method VARCHAR(10) PATH '$')) m;

INSERT INTO route_allowed_headers (route_pk, header_name)
SELECT r.id, h.header_name
FROM seed_routes s
         JOIN organizations o ON o.slug = s.slug
         JOIN api_routes r ON r.organization_id = o.id AND r.route_id = s.route_id AND r.id > @routes_before,
     JSON_TABLE(s.headers, '$[*]' COLUMNS (header_name VARCHAR(200) PATH '$')) h;

-- ---------- header rules the gateway applies ----------
CREATE TEMPORARY TABLE seed_header_rules
(
    slug         VARCHAR(100),
    route_id     VARCHAR(100),
    direction    VARCHAR(20),
    action       VARCHAR(20),
    header_name  VARCHAR(200),
    header_value VARCHAR(2000)
);

INSERT INTO seed_header_rules
VALUES ('acme', 'orders', 'REQUEST', 'SET', 'x-source', 'gatekeeper'),
       ('acme', 'inventory', 'RESPONSE', 'SET', 'cache-control', 'public, max-age=30'),
       ('acme', 'payment', 'RESPONSE', 'REMOVE', 'server', NULL),
       ('globex', 'checkout', 'REQUEST', 'ADD', 'x-channel', 'web'),
       ('globex', 'catalog', 'RESPONSE', 'SET', 'cache-control', 'public, max-age=60'),
       ('initech', 'reports', 'RESPONSE', 'SET', 'cache-control', 'no-store'),
       ('umbrella', 'patient', 'RESPONSE', 'SET', 'cache-control', 'no-store'),
       ('umbrella', 'lab-results', 'RESPONSE', 'REMOVE', 'x-powered-by', NULL),
       ('stark', 'telemetry', 'REQUEST', 'SET', 'x-ingest-pipeline', 'realtime'),
       ('wayne', 'tracking', 'RESPONSE', 'SET', 'cache-control', 'public, max-age=15');

INSERT INTO route_header_rules (route_pk, direction, action, header_name, header_value, active, date_created, date_updated)
SELECT r.id, s.direction, s.action, s.header_name, s.header_value, TRUE, NOW(6), NOW(6)
FROM seed_header_rules s
         JOIN organizations o ON o.slug = s.slug
         JOIN api_routes r ON r.organization_id = o.id AND r.route_id = s.route_id AND r.id > @routes_before;

-- A new version for every sample organization, so gateways and analytics take the data on the next publish
-- (api-management publishes every organization when it starts)
UPDATE organizations o
    JOIN seed_organizations s ON s.slug = o.slug
SET o.configuration_version = o.configuration_version + 1,
    o.date_updated          = NOW(6);

DROP TEMPORARY TABLE seed_header_rules;
DROP TEMPORARY TABLE seed_routes;
DROP TEMPORARY TABLE seed_organizations;
