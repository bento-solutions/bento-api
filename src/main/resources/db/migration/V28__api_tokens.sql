-- Personal API tokens for AI agents and integrations. The token itself is shown once at creation
-- and only its SHA-256 is stored; token_prefix is kept to recognise a token in lists and logs.
CREATE TABLE api_token (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL REFERENCES organization(id),
    user_id            UUID NOT NULL,
    name               VARCHAR(100) NOT NULL,
    token_prefix       VARCHAR(32) NOT NULL,
    token_hash         VARCHAR(64) NOT NULL UNIQUE,
    -- whatsapp:read | whatsapp:draft | whatsapp:send | partners:read
    scopes             JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- Messages (sent or drafted) this token may create per rolling hour.
    max_sends_per_hour INTEGER,
    expires_at         TIMESTAMPTZ,
    last_used_at       TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by         UUID,
    updated_by         UUID,
    version            BIGINT NOT NULL DEFAULT 0,
    deleted_at         TIMESTAMPTZ,
    CONSTRAINT uq_api_token_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_api_token_user FOREIGN KEY (organization_id, user_id)
        REFERENCES app_user (organization_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_api_token_user ON api_token (organization_id, user_id);

-- Which token an agent message came from: audit trail and the per-token hourly cap.
ALTER TABLE wa_message ADD CONSTRAINT fk_wa_message_api_token
    FOREIGN KEY (organization_id, api_token_id) REFERENCES api_token (organization_id, id)
    ON DELETE SET NULL (api_token_id);
CREATE INDEX idx_wa_message_api_token ON wa_message (api_token_id, created_at)
    WHERE api_token_id IS NOT NULL;
