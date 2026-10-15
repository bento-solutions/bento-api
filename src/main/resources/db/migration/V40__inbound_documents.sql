-- Dedicated email inboxes per organization (vendor invoices, bank statements) and the queue of
-- documents that arrive through them, through WhatsApp or by upload, waiting to be read by the AI
-- and booked, or reviewed by a person.
CREATE TABLE inbound_mailbox (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    kind                VARCHAR(30) NOT NULL,
    -- Local part of the address: <token>@<app.inbound.domain>.
    token               VARCHAR(60) NOT NULL UNIQUE,
    -- Senders whose invoices may be booked without review (addresses or @domain).
    allowed_senders     TEXT[] NOT NULL DEFAULT '{}',
    auto_book           BOOLEAN NOT NULL DEFAULT TRUE,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_inbound_mailbox_kind ON inbound_mailbox (organization_id, kind) WHERE deleted_at IS NULL;

CREATE TABLE inbound_document (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    mailbox_id          UUID REFERENCES inbound_mailbox(id),
    channel             VARCHAR(20) NOT NULL,
    kind                VARCHAR(30) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    file_id             UUID NOT NULL REFERENCES stored_file(id),
    sender              VARCHAR(320),
    sender_verified     BOOLEAN NOT NULL DEFAULT FALSE,
    subject             VARCHAR(500),
    message_id          VARCHAR(500),
    received_at         TIMESTAMPTZ NOT NULL,
    attempts            INT NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    extraction          JSONB,
    confidence          NUMERIC(4, 3),
    review_reason       VARCHAR(255),
    partner_id          UUID REFERENCES partner(id),
    invoice_id          UUID REFERENCES invoice(id),
    error               VARCHAR(500),
    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_inbound_document_org_status ON inbound_document (organization_id, status, received_at DESC);
CREATE INDEX idx_inbound_document_queue ON inbound_document (status, kind, next_attempt_at) WHERE status = 'QUEUED';

-- Documents a known contact sends on WhatsApp are filed in their drive folder.
ALTER TABLE organization ADD COLUMN whatsapp_documents BOOLEAN NOT NULL DEFAULT TRUE;
