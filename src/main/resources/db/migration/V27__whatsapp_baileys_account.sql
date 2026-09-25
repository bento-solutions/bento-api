-- A WaAccount can now be a personal number linked through the Baileys bot (provider BAILEYS):
-- the bot's session state is mirrored here, and each organization decides how its number paces
-- outreach, whether unknown numbers become leads, and who sees which conversations.

ALTER TABLE wa_account
    -- Mirrors the bot session (stopped, needs_pairing, pairing, open, reconnecting, logged_out,
    -- replaced, pairing_failed, error). session_seq orders the bot's status events.
    ADD COLUMN session_state            VARCHAR(30),
    ADD COLUMN session_seq              BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN pairing_code             VARCHAR(20),
    ADD COLUMN pairing_expires_at       TIMESTAMPTZ,
    ADD COLUMN pairing_attempt          INTEGER,
    ADD COLUMN requested_phone          VARCHAR(32),
    ADD COLUMN linked_phone             VARCHAR(32),
    ADD COLUMN linked_at                TIMESTAMPTZ,
    ADD COLUMN last_seen_at             TIMESTAMPTZ,
    ADD COLUMN session_error            VARCHAR(500),
    -- Pacing overrides for a paced account; NULL falls back to whatsapp.outbox.pacing.*.
    ADD COLUMN reply_min_gap_seconds    INTEGER,
    ADD COLUMN outreach_min_gap_seconds INTEGER,
    ADD COLUMN outreach_per_hour        INTEGER,
    ADD COLUMN new_chats_per_day        INTEGER,
    -- OFF | INBOUND (contact writes first) | INBOUND_AND_PHONE (also when the owner writes from
    -- the phone). New accounts start OFF so linking a personal number never floods the CRM.
    ADD COLUMN auto_create_leads        VARCHAR(30) NOT NULL DEFAULT 'OFF',
    -- ASSIGNED: users see conversations of partners assigned to/owned by them (plus everything
    -- with WHATSAPP_READ_ALL). ALL: everyone with WHATSAPP_READ sees every conversation.
    ADD COLUMN visibility               VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',
    ADD COLUMN default_assignee_user_id UUID;

ALTER TABLE wa_account ADD CONSTRAINT fk_wa_account_default_assignee
    FOREIGN KEY (organization_id, default_assignee_user_id) REFERENCES app_user (organization_id, id)
    ON DELETE SET NULL (default_assignee_user_id);

-- Pacing counts for a paced account: last send, outreach per hour, new chats per day.
CREATE INDEX idx_wa_message_sent ON wa_message (organization_id, sent_at DESC)
    WHERE direction = 'OUT' AND sent_at IS NOT NULL;
