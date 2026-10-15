-- Expenses (light): what the organization spent, by category, with receipts attached as files
-- (owner type EXPENSE). Prepares the cash-management phase.
CREATE TABLE expense (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    expense_date        DATE NOT NULL,
    category            VARCHAR(60) NOT NULL,
    description         VARCHAR(255),
    amount              NUMERIC(15, 2) NOT NULL,
    vat_amount          NUMERIC(15, 2),
    payment_method      VARCHAR(20) NOT NULL,
    vendor_partner_id   UUID REFERENCES partner(id),
    vendor_name         VARCHAR(255),
    reference           VARCHAR(100),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_expense_org_date ON expense (organization_id, expense_date DESC);
