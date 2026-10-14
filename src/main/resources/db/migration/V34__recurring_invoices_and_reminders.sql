-- Recurring invoices: a template that a scheduled job turns into a real invoice on each period.
-- next_run_date is derived from start_date + period_index periods, so a monthly template
-- started on the 31st lands on the last day of shorter months without drifting to the 28th.
-- period_index also moves past periods skipped while paused; generated_count counts invoices.
CREATE TABLE recurring_invoice (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    partner_id          UUID NOT NULL,
    name                VARCHAR(255) NOT NULL,
    -- WEEKLY | MONTHLY | QUARTERLY | YEARLY, repeated every interval_count periods
    frequency           VARCHAR(20) NOT NULL,
    interval_count      INTEGER NOT NULL DEFAULT 1 CHECK (interval_count BETWEEN 1 AND 24),
    start_date          DATE NOT NULL,
    end_date            DATE,
    next_run_date       DATE,
    -- ACTIVE | PAUSED | ENDED
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    payment_terms_days  INTEGER NOT NULL DEFAULT 30 CHECK (payment_terms_days BETWEEN 0 AND 365),
    -- true: each generated invoice is marked SENT and emailed with its PDF; false: left as DRAFT
    auto_send           BOOLEAN NOT NULL DEFAULT TRUE,
    customer_name       VARCHAR(255),
    delivery_address    TEXT,
    vat_number          VARCHAR(50),
    lines               JSONB NOT NULL DEFAULT '[]'::jsonb,
    period_index        INTEGER NOT NULL DEFAULT 0,
    generated_count     INTEGER NOT NULL DEFAULT 0,
    last_run_at         TIMESTAMPTZ,
    last_invoice_id     UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_recurring_invoice_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_recurring_invoice_partner FOREIGN KEY (organization_id, partner_id)
        REFERENCES partner (organization_id, id)
);

CREATE INDEX idx_recurring_invoice_due ON recurring_invoice (status, next_run_date) WHERE deleted_at IS NULL;
CREATE INDEX idx_recurring_invoice_org ON recurring_invoice (organization_id, status);

-- Which template an invoice came from, and the reminders it has had.
ALTER TABLE invoice ADD COLUMN recurring_invoice_id UUID;
ALTER TABLE invoice ADD COLUMN reminder_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE invoice ADD COLUMN last_reminder_at TIMESTAMPTZ;
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_recurring_invoice
    FOREIGN KEY (organization_id, recurring_invoice_id) REFERENCES recurring_invoice (organization_id, id)
    ON DELETE SET NULL (recurring_invoice_id);
CREATE INDEX idx_invoice_recurring ON invoice (organization_id, recurring_invoice_id)
    WHERE recurring_invoice_id IS NOT NULL;

-- One reminder policy per organization. offsets_days are days relative to the due date:
-- -3 = three days before, 7 = a week after. Off until the organization turns it on.
CREATE TABLE invoice_reminder_policy (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    enabled             BOOLEAN NOT NULL DEFAULT FALSE,
    offsets_days        JSONB NOT NULL DEFAULT '[-3, 1, 7, 15]'::jsonb,
    -- EMAIL only for now; WHATSAPP is planned
    channel             VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    message             TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_invoice_reminder_policy_org UNIQUE (organization_id)
);

-- Every automatic reminder attempt. The unique key is what keeps a reminder from going out twice,
-- even when two application instances run the job at the same time.
CREATE TABLE invoice_reminder_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    invoice_id          UUID NOT NULL,
    offset_days         INTEGER NOT NULL,
    channel             VARCHAR(20) NOT NULL,
    -- SENT | SKIPPED_NO_EMAIL
    outcome             VARCHAR(30) NOT NULL,
    recipient           VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_invoice_reminder_log_offset UNIQUE (invoice_id, offset_days),
    CONSTRAINT fk_invoice_reminder_log_invoice FOREIGN KEY (organization_id, invoice_id)
        REFERENCES invoice (organization_id, id) ON DELETE CASCADE
);

CREATE INDEX idx_invoice_reminder_log_org ON invoice_reminder_log (organization_id, created_at DESC);
