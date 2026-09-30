CREATE TABLE auth_families (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revocation_reason varchar(32),
    CHECK (expires_at > created_at),
    CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);
CREATE INDEX auth_families_account_idx ON auth_families(account_id);
CREATE TABLE auth_access_tokens (
    token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    family_id uuid NOT NULL REFERENCES auth_families(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX auth_access_family_idx ON auth_access_tokens(family_id);
CREATE TABLE auth_refresh_tokens (
    token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    family_id uuid NOT NULL REFERENCES auth_families(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);
CREATE INDEX auth_refresh_family_idx ON auth_refresh_tokens(family_id);

CREATE TABLE spring_session (
    primary_id char(36) PRIMARY KEY,
    session_id char(36) NOT NULL UNIQUE,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100)
);
CREATE INDEX spring_session_expiry_idx ON spring_session(expiry_time);
CREATE INDEX spring_session_principal_idx ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);
