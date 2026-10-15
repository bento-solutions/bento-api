-- Pay-by-Link: how each organization takes card payments, and one public link per invoice.

CREATE TABLE payment_settings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    provider            VARCHAR(20) NOT NULL DEFAULT 'NONE',
    include_in_emails   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_payment_settings_org UNIQUE (organization_id)
);

CREATE TABLE payment_link (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    invoice_id          UUID NOT NULL REFERENCES invoice(id),
    token               VARCHAR(64) NOT NULL,
    provider            VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    provider_session_id VARCHAR(255),
    amount              NUMERIC(15, 2),
    currency            VARCHAR(3) NOT NULL DEFAULT 'MAD',
    paid_at             TIMESTAMPTZ,
    payment_id          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_payment_link_token UNIQUE (token)
);

CREATE INDEX idx_payment_link_invoice ON payment_link (organization_id, invoice_id);
CREATE UNIQUE INDEX uq_payment_link_session ON payment_link (provider_session_id) WHERE provider_session_id IS NOT NULL;
