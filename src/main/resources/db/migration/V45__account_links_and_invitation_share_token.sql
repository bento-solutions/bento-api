-- One-time links emailed to an Account: confirming its address, and choosing a new password.
-- Only a SHA-256 of each link's token is stored, like refresh and invitation tokens.
CREATE TABLE account_token (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  UUID NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    purpose     VARCHAR(30) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_account_token_purpose CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD'))
);

CREATE INDEX idx_account_token_account_purpose ON account_token (account_id, purpose, created_at DESC);

-- An invitation now has two tokens. token_hash is the one only the invitation email carries, so
-- accepting with it proves the invitee reads that inbox. share_token_hash is the link an admin can
-- copy and pass on another way; it lets the person join but doesn't verify their address.
ALTER TABLE user_invitation ADD COLUMN share_token_hash VARCHAR(64) UNIQUE;

-- raw_token is what the admin screen shows as the link to copy. From now on it only ever holds the
-- share token; the emailed tokens it held for pending invitations are cleared. Resending such an
-- invitation issues both tokens.
UPDATE user_invitation SET raw_token = NULL WHERE share_token_hash IS NULL;
