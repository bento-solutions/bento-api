-- WhatsApp inbox groundwork: what the conversation list renders without scanning messages,
-- where each message came from, and the columns the outbound queue works from.
--
-- Conversation activity columns (last_message_*, unread_count, window, timestamps) are only
-- ever written by single conditional UPDATE statements, never read-modify-write through the
-- entity: inbound messages and receipts for one conversation can land concurrently, and a
-- versioned entity update would reject all but the first of them.

ALTER TABLE wa_conversation
    ADD COLUMN wa_jid                 VARCHAR(128),
    ADD COLUMN wa_lid                 VARCHAR(128),
    ADD COLUMN display_name           VARCHAR(255),
    ADD COLUMN last_message_at        TIMESTAMPTZ,
    ADD COLUMN last_message_preview   VARCHAR(280),
    ADD COLUMN last_message_direction VARCHAR(3),
    ADD COLUMN unread_count           INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_read_at           TIMESTAMPTZ,
    ADD COLUMN last_notified_at       TIMESTAMPTZ;

ALTER TABLE wa_message
    -- Who produced the message: CONTACT (inbound), HUMAN (typed in the CRM), AGENT (API
    -- token), CAMPAIGN (campaign send or relance), PHONE (typed on the linked phone).
    ADD COLUMN source              VARCHAR(20),
    -- When it happened on WhatsApp. created_at is only when the CRM learned about it, which
    -- for a message received while the bot was offline can be much later.
    ADD COLUMN occurred_at         TIMESTAMPTZ,
    ADD COLUMN sent_at             TIMESTAMPTZ,
    ADD COLUMN delivered_at        TIMESTAMPTZ,
    ADD COLUMN read_at             TIMESTAMPTZ,
    -- Outbound queue. Status additionally takes DRAFT, SENDING and CANCELLED; it is a
    -- varchar, so no DDL is needed for that.
    ADD COLUMN lane                VARCHAR(20),
    ADD COLUMN new_chat            BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN priority            INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN not_before          TIMESTAMPTZ,
    ADD COLUMN claimed_at          TIMESTAMPTZ,
    ADD COLUMN attempts            INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN api_token_id        UUID,
    ADD COLUMN approved_by_user_id UUID,
    ADD COLUMN client_ref          VARCHAR(100),
    ADD COLUMN media_type          VARCHAR(20),
    ADD COLUMN mime_type           VARCHAR(100),
    ADD COLUMN file_name           VARCHAR(255),
    ADD COLUMN quoted_wamid        VARCHAR(128);

UPDATE wa_message SET occurred_at = created_at WHERE occurred_at IS NULL;
UPDATE wa_message
SET source = CASE
        WHEN direction = 'IN' THEN 'CONTACT'
        WHEN campaign_id IS NOT NULL THEN 'CAMPAIGN'
        ELSE 'HUMAN'
    END
WHERE source IS NULL;
UPDATE wa_message SET sent_at = created_at
WHERE direction = 'OUT' AND status IN ('SENT', 'DELIVERED', 'READ') AND sent_at IS NULL;

ALTER TABLE wa_message
    ALTER COLUMN occurred_at SET NOT NULL,
    ALTER COLUMN occurred_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN source SET NOT NULL;

UPDATE wa_conversation c
SET last_message_at        = m.occurred_at,
    last_message_preview   = left(m.body, 280),
    last_message_direction = m.direction
FROM (SELECT DISTINCT ON (conversation_id) conversation_id, occurred_at, body, direction
      FROM wa_message
      WHERE deleted_at IS NULL
      ORDER BY conversation_id, occurred_at DESC, id DESC) m
WHERE m.conversation_id = c.id;

-- wamid was unique across all tenants. With personal numbers linked through Baileys, two
-- organizations that message each other both see the same message id, and a global key would
-- drop one side's copy as a "duplicate". Idempotency is per organization.
ALTER TABLE wa_message DROP CONSTRAINT IF EXISTS wa_message_wamid_key;
ALTER TABLE wa_message ADD CONSTRAINT uq_wa_message_org_wamid UNIQUE (organization_id, wamid);

-- A number the organization chose to ignore: nothing from or to it is stored.
CREATE TABLE wa_blocked_number (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    phone_e164      VARCHAR(32) NOT NULL,
    reason          VARCHAR(255),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by      UUID,
    updated_by      UUID,
    version         BIGINT NOT NULL DEFAULT 0,
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT uq_wa_blocked_number UNIQUE (organization_id, phone_e164)
);

-- Inbox list, newest activity first.
CREATE INDEX idx_wa_conversation_inbox
    ON wa_conversation (organization_id, last_message_at DESC NULLS LAST)
    WHERE deleted_at IS NULL;
-- Resolving a Baileys LID-addressed message back to its conversation.
CREATE INDEX idx_wa_conversation_lid
    ON wa_conversation (organization_id, wa_lid)
    WHERE wa_lid IS NOT NULL;

-- Thread paging (newest first, stable tie-break on id) replaces the created_at index.
DROP INDEX IF EXISTS idx_wa_message_conversation;
CREATE INDEX idx_wa_message_thread ON wa_message (conversation_id, occurred_at DESC, id DESC);
-- Outbox claim and the reaper for sends that never completed.
CREATE INDEX idx_wa_message_outbox ON wa_message (organization_id, priority DESC, created_at)
    WHERE status = 'QUEUED';
CREATE INDEX idx_wa_message_sending ON wa_message (claimed_at)
    WHERE status = 'SENDING';
-- Client-supplied idempotency key for sends (a retried API call must not send twice).
CREATE UNIQUE INDEX uq_wa_message_client_ref ON wa_message (organization_id, client_ref)
    WHERE client_ref IS NOT NULL;

-- Serves PartnerRepository.findByOrganizationIdAndPhoneDigits, which every inbound message
-- runs; the expression must stay identical to the one in that query.
CREATE INDEX idx_partner_phone_last9
    ON partner (organization_id, right(regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g'), 9))
    WHERE deleted_at IS NULL;
