-- V24: Invitation enhancements - store raw token for manual link copying and indexing for cross-tenant email lookup

ALTER TABLE user_invitation ADD COLUMN raw_token VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_user_invitation_email_status
    ON user_invitation(lower(email), status);
