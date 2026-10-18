-- How the gateway verifies tenant user tokens: the tenant's own auth server (issuer), where its public keys are
-- published (JWKS URL) and which audience the tokens must carry. One row per organization.
CREATE TABLE organization_token_settings
(
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id BIGINT        NOT NULL,
    issuer          VARCHAR(500)  NOT NULL,
    jwks_uri        VARCHAR(1000) NOT NULL,
    audience        VARCHAR(200)  NOT NULL,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    date_created    DATETIME(6)   NOT NULL,
    date_updated    DATETIME(6)   NOT NULL,
    CONSTRAINT pk_organization_token_settings PRIMARY KEY (id),
    CONSTRAINT uk_organization_token_settings_org UNIQUE (organization_id),
    CONSTRAINT fk_organization_token_settings_org FOREIGN KEY (organization_id) REFERENCES organizations (id)
);

-- Request headers the gateway forwards for a route; everything else is dropped
-- (Content-Type and Accept are always forwarded and need no row here)
CREATE TABLE route_allowed_headers
(
    route_pk    BIGINT       NOT NULL,
    header_name VARCHAR(200) NOT NULL,
    CONSTRAINT pk_route_allowed_headers PRIMARY KEY (route_pk, header_name),
    CONSTRAINT fk_route_allowed_headers_route FOREIGN KEY (route_pk) REFERENCES api_routes (id)
);
