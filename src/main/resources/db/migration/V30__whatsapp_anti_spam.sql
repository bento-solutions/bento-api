-- Anti-spam safeguards for a personal number linked through the Baileys bot (provider BAILEYS):
-- a circuit breaker on outreach, a warm-up clock, and WhatsApp's own restriction and new-chat quota
-- for the number, as the bot reports them. See docs/whatsapp-anti-spam-policy.md.

ALTER TABLE wa_account
    -- Outreach (first messages, relances, campaigns) waits until this time; replies still go out.
    -- Set automatically when WhatsApp restricts, warns or logs the number out, or by an admin.
    ADD COLUMN outreach_paused_until  TIMESTAMPTZ,
    ADD COLUMN outreach_pause_reason  VARCHAR(300),
    -- Start of the warm-up: no outreach during a quiet period, then a rising daily cap on new
    -- chats. Set when the number is linked and moved to the end of any restriction.
    ADD COLUMN warmup_started_at      TIMESTAMPTZ,
    -- WhatsApp's reachout timelock: while it lasts the number may not start new chats.
    ADD COLUMN reachout_locked_until  TIMESTAMPTZ,
    ADD COLUMN reachout_enforcement   VARCHAR(60),
    -- WhatsApp's quota of first messages for the current cycle, and how close the number is to
    -- being capped (NONE, FIRST_WARNING, SECOND_WARNING, CAPPED).
    ADD COLUMN new_chat_quota         INTEGER,
    ADD COLUMN new_chat_quota_used    INTEGER,
    ADD COLUMN new_chat_cap_status    VARCHAR(30),
    ADD COLUMN new_chat_cycle_ends_at TIMESTAMPTZ;

UPDATE wa_account SET warmup_started_at = linked_at WHERE provider = 'BAILEYS' AND linked_at IS NOT NULL;

-- The settings now refuse values this loose; drop such overrides so the safer defaults apply.
UPDATE wa_account SET reply_min_gap_seconds = NULL WHERE reply_min_gap_seconds < 5;
UPDATE wa_account SET outreach_min_gap_seconds = NULL WHERE outreach_min_gap_seconds < 60;
UPDATE wa_account SET outreach_per_hour = NULL WHERE outreach_per_hour > 15;
UPDATE wa_account SET new_chats_per_day = NULL WHERE new_chats_per_day > 25;
