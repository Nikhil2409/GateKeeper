-- X-Correlation-Id is now set by the gateway, so tenants can no longer allowlist it. Existing allowlist entries are
-- removed; the gateway already ignored them, so traffic does not change.
DELETE FROM route_allowed_headers
WHERE header_name = 'x-correlation-id';
