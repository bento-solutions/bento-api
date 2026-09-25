-- Campaigns on a linked personal number (provider BAILEYS) send plain text through the paced
-- outbox instead of Meta templates: the relance needs its own text too.
ALTER TABLE campaign ADD COLUMN followup_body TEXT;

-- A campaign leaves SENDING when none of its messages are still waiting in the outbox.
CREATE INDEX idx_wa_message_campaign_pending ON wa_message (campaign_id)
    WHERE status IN ('QUEUED', 'SENDING');
