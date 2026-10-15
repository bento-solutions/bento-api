-- Cash management: the company's bank accounts, the transactions read from their statements,
-- categorization rules, and the link from a transaction to the invoice, payment or expense it settles.
CREATE TABLE bank_account (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    name                VARCHAR(120) NOT NULL,
    bank                VARCHAR(30) NOT NULL,
    -- RIB (24 digits in Morocco) or any account number; matched on digits only.
    account_number      VARCHAR(40) NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'MAD',
    opening_balance     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    opening_date        DATE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_bank_account_number ON bank_account (organization_id, account_number) WHERE deleted_at IS NULL;

CREATE TABLE bank_import (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    bank_account_id     UUID NOT NULL REFERENCES bank_account(id),
    file_id             UUID REFERENCES stored_file(id),
    inbound_document_id UUID REFERENCES inbound_document(id),
    format              VARCHAR(10) NOT NULL,
    parser              VARCHAR(40) NOT NULL,
    period_start        DATE,
    period_end          DATE,
    opening_balance     NUMERIC(19, 2),
    closing_balance     NUMERIC(19, 2),
    rows_imported       INT NOT NULL DEFAULT 0,
    rows_skipped        INT NOT NULL DEFAULT 0,
    -- The statement's own closing balance does not match opening + movements.
    balance_mismatch    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_bank_import_account ON bank_import (bank_account_id, created_at DESC);

CREATE TABLE bank_transaction (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    bank_account_id     UUID NOT NULL REFERENCES bank_account(id),
    import_id           UUID REFERENCES bank_import(id),
    booking_date        DATE NOT NULL,
    value_date          DATE,
    description         VARCHAR(500) NOT NULL,
    reference           VARCHAR(120),
    -- Positive: money in. Negative: money out.
    amount              NUMERIC(19, 2) NOT NULL,
    balance_after       NUMERIC(19, 2),
    category            VARCHAR(40),
    category_source     VARCHAR(10),
    status              VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
    invoice_id          UUID REFERENCES invoice(id),
    payment_id          UUID REFERENCES payment(id),
    expense_id          UUID REFERENCES expense(id),
    -- Our own payment, created when the transaction was matched to an invoice (removed on unmatch).
    payment_created     BOOLEAN NOT NULL DEFAULT FALSE,
    -- Same date, amount and text in the same account: the same line seen in two statements.
    fingerprint         VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_bank_transaction_fingerprint ON bank_transaction (bank_account_id, fingerprint) WHERE deleted_at IS NULL;
CREATE INDEX idx_bank_transaction_org_date ON bank_transaction (organization_id, booking_date DESC);
CREATE INDEX idx_bank_transaction_status ON bank_transaction (organization_id, status);

CREATE TABLE bank_rule (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    -- Case-insensitive text the description must contain.
    contains_text       VARCHAR(120) NOT NULL,
    direction           VARCHAR(5) NOT NULL DEFAULT 'ANY',
    category            VARCHAR(40) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_bank_rule_org ON bank_rule (organization_id) WHERE deleted_at IS NULL;
