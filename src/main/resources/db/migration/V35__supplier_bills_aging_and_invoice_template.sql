-- Supplier bills: the day the organization plans to pay a bill, so payables can be scheduled.
ALTER TABLE invoice ADD COLUMN scheduled_payment_date DATE;

-- One "bills due soon / overdue" digest per organization per day, however many instances run the job.
CREATE TABLE bill_alert_digest (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    alert_date          DATE NOT NULL,
    due_soon_count      INTEGER NOT NULL DEFAULT 0,
    overdue_count       INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_bill_alert_digest_day UNIQUE (organization_id, alert_date)
);

-- What the organization prints on its invoices: Moroccan legal identifiers, contact and bank
-- details, an accent colour, a logo and a footer. One row per organization; brands can override
-- the logo and footer.
CREATE TABLE invoice_template (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    legal_name          VARCHAR(255),
    address             TEXT,
    phone               VARCHAR(50),
    email               VARCHAR(255),
    website             VARCHAR(255),
    ice                 VARCHAR(30),
    if_number           VARCHAR(30),
    rc                  VARCHAR(50),
    patente             VARCHAR(30),
    cnss                VARCHAR(30),
    capital             VARCHAR(50),
    bank_name           VARCHAR(100),
    rib                 VARCHAR(50),
    accent_color        VARCHAR(7),
    logo_file_id        UUID,
    payment_terms_text  TEXT,
    footer_text         TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_invoice_template_org UNIQUE (organization_id)
);

ALTER TABLE brand ADD COLUMN invoice_logo_file_id UUID;
ALTER TABLE brand ADD COLUMN invoice_footer TEXT;
