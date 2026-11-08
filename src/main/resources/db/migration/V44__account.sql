-- An Account is a person who signs in; each app_user row is one of their Memberships in an
-- Organization (docs/adr/0001-accounts-hold-several-memberships.md). The password, and later the
-- email verification, belong to the Account so a person has one of each, not one per Organization.
CREATE TABLE account (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    email_verified_at   TIMESTAMP WITH TIME ZONE,
    password_changed_at TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version             BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_account_email_lowercase CHECK (email = lower(email))
);

CREATE UNIQUE INDEX uq_account_email ON account (email);

-- One Account per address, ignoring case and surrounding spaces. Where the same person had a
-- different password in different Organizations, the Membership they used most recently decides.
INSERT INTO account (email, password_hash, created_at, updated_at)
SELECT address, password_hash, first_created_at, CURRENT_TIMESTAMP
FROM (
    SELECT DISTINCT ON (lower(btrim(email)))
           lower(btrim(email)) AS address,
           password_hash,
           min(created_at) OVER (PARTITION BY lower(btrim(email))) AS first_created_at
    FROM app_user
    ORDER BY lower(btrim(email)), last_active_at DESC NULLS LAST, updated_at DESC, created_at DESC
) latest;

ALTER TABLE app_user ADD COLUMN account_id UUID REFERENCES account (id);

UPDATE app_user u
SET account_id = a.id
FROM account a
WHERE a.email = lower(btrim(u.email));

ALTER TABLE app_user ALTER COLUMN account_id SET NOT NULL;
CREATE INDEX idx_app_user_account ON app_user (account_id);

-- A person holds at most one Membership in each Organization.
CREATE UNIQUE INDEX uq_app_user_account_organization ON app_user (account_id, organization_id);

-- The application no longer reads or writes app_user.password_hash. It stays, nullable, so the
-- previous release can still sign people in if this one has to be rolled back; a later migration
-- drops it.
ALTER TABLE app_user ALTER COLUMN password_hash DROP NOT NULL;
