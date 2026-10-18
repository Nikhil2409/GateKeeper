-- The gateway no longer calls this service: configuration reaches it over Kafka (decision #39).
-- The service account and its role are removed outright; nothing else references them.
DELETE FROM management_users
WHERE role_id = (SELECT id FROM (SELECT id FROM roles WHERE name = 'GATEWAY_SERVICE') AS gateway_role);

DELETE FROM roles
WHERE name = 'GATEWAY_SERVICE';
