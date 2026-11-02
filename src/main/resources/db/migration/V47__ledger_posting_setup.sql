-- General ledger, phase 2: posting groups and automatic postings.
--
-- Invoices, supplier bills, payments, expenses and bank lines post their journal entries through
-- the posting setup below (seeded per organization on its first ledger call, like the accounts).
-- Every reference between two tenant tables stays a composite (organization_id, x_id) key.

-- New organizations work in dirhams.
ALTER TABLE organization ALTER COLUMN default_currency SET DEFAULT 'MAD';

-- Composite-FK parents.
ALTER TABLE bank_account ADD CONSTRAINT uq_bank_account_org_id UNIQUE (organization_id, id);
ALTER TABLE product ADD CONSTRAINT uq_product_org_id UNIQUE (organization_id, id);

-- Partner posting groups: which receivable (customers) or payable (suppliers) account a partner uses.
CREATE TABLE ledger_partner_group (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    code                VARCHAR(20) NOT NULL,
    name                VARCHAR(160) NOT NULL,
    side                VARCHAR(10) NOT NULL CHECK (side IN ('CUSTOMER', 'SUPPLIER')),
    account_id          UUID NOT NULL,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_partner_group_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_ledger_partner_group_code UNIQUE (organization_id, side, code),
    CONSTRAINT fk_ledger_partner_group_account
        FOREIGN KEY (organization_id, account_id) REFERENCES ledger_account (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_partner_group_default ON ledger_partner_group (organization_id, side) WHERE is_default;

-- Business posting groups (where the partner trades: Morocco, export) and product posting groups
-- (what is sold or bought: goods, services). Their pair picks the sales and purchase accounts.
CREATE TABLE ledger_business_group (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    code                VARCHAR(20) NOT NULL,
    name                VARCHAR(160) NOT NULL,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_business_group_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_ledger_business_group_code UNIQUE (organization_id, code)
);
CREATE UNIQUE INDEX uq_ledger_business_group_default ON ledger_business_group (organization_id) WHERE is_default;

CREATE TABLE ledger_product_group (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    code                VARCHAR(20) NOT NULL,
    name                VARCHAR(160) NOT NULL,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_product_group_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_ledger_product_group_code UNIQUE (organization_id, code)
);
CREATE UNIQUE INDEX uq_ledger_product_group_default ON ledger_product_group (organization_id) WHERE is_default;

-- One row per business group x product group: the sales and purchase accounts of that pair.
CREATE TABLE ledger_general_setup (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    business_group_id   UUID NOT NULL,
    product_group_id    UUID NOT NULL,
    sales_account_id    UUID NOT NULL,
    purchase_account_id UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ledger_general_setup_pair UNIQUE (organization_id, business_group_id, product_group_id),
    CONSTRAINT fk_ledger_general_setup_business
        FOREIGN KEY (organization_id, business_group_id) REFERENCES ledger_business_group (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_ledger_general_setup_product
        FOREIGN KEY (organization_id, product_group_id) REFERENCES ledger_product_group (organization_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_ledger_general_setup_sales
        FOREIGN KEY (organization_id, sales_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_general_setup_purchase
        FOREIGN KEY (organization_id, purchase_account_id) REFERENCES ledger_account (organization_id, id)
);

-- VAT posting setup, one row per rate (and the exempt cases).
CREATE TABLE ledger_vat_setup (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organization(id),
    code                    VARCHAR(20) NOT NULL,
    name                    VARCHAR(160) NOT NULL,
    rate                    NUMERIC(5, 2) NOT NULL CHECK (rate >= 0 AND rate <= 100),
    collected_account_id    UUID NOT NULL,
    recoverable_account_id  UUID NOT NULL,
    exempt                  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ledger_vat_setup_code UNIQUE (organization_id, code),
    CONSTRAINT fk_ledger_vat_setup_collected
        FOREIGN KEY (organization_id, collected_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_vat_setup_recoverable
        FOREIGN KEY (organization_id, recoverable_account_id) REFERENCES ledger_account (organization_id, id)
);

-- The account of each bank line category (kind BANK) and expense category (kind EXPENSE).
CREATE TABLE ledger_category_account (
    organization_id     UUID NOT NULL REFERENCES organization(id),
    kind                VARCHAR(10) NOT NULL CHECK (kind IN ('BANK', 'EXPENSE')),
    category            VARCHAR(60) NOT NULL,
    account_id          UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, kind, category),
    CONSTRAINT fk_ledger_category_account_account
        FOREIGN KEY (organization_id, account_id) REFERENCES ledger_account (organization_id, id)
);

-- Per-organization posting settings: the first day automatic postings cover, and the cash account.
CREATE TABLE ledger_settings (
    organization_id     UUID PRIMARY KEY REFERENCES organization(id),
    start_date          DATE NOT NULL,
    cash_account_id     UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ledger_settings_cash
        FOREIGN KEY (organization_id, cash_account_id) REFERENCES ledger_account (organization_id, id)
);

-- Each bank account posts to its own 5141 sub-account.
ALTER TABLE bank_account ADD COLUMN ledger_account_id UUID;
ALTER TABLE bank_account ADD CONSTRAINT fk_bank_account_ledger_account
    FOREIGN KEY (organization_id, ledger_account_id) REFERENCES ledger_account (organization_id, id);

-- Group assignments; null means the default group.
ALTER TABLE partner ADD COLUMN customer_group_id UUID;
ALTER TABLE partner ADD COLUMN supplier_group_id UUID;
ALTER TABLE partner ADD COLUMN business_group_id UUID;
ALTER TABLE partner ADD CONSTRAINT fk_partner_customer_group
    FOREIGN KEY (organization_id, customer_group_id) REFERENCES ledger_partner_group (organization_id, id);
ALTER TABLE partner ADD CONSTRAINT fk_partner_supplier_group
    FOREIGN KEY (organization_id, supplier_group_id) REFERENCES ledger_partner_group (organization_id, id);
ALTER TABLE partner ADD CONSTRAINT fk_partner_business_group
    FOREIGN KEY (organization_id, business_group_id) REFERENCES ledger_business_group (organization_id, id);

ALTER TABLE product ADD COLUMN product_group_id UUID;
ALTER TABLE product ADD CONSTRAINT fk_product_product_group
    FOREIGN KEY (organization_id, product_group_id) REFERENCES ledger_product_group (organization_id, id);

-- Payments say which bank account (or the cash register) the money went through.
ALTER TABLE payment ADD COLUMN bank_account_id UUID;
ALTER TABLE payment ADD COLUMN cash_register BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment ADD CONSTRAINT fk_payment_bank_account
    FOREIGN KEY (organization_id, bank_account_id) REFERENCES bank_account (organization_id, id);

-- An expense can carry an analytic cost center.
ALTER TABLE expense ADD COLUMN cost_center_id UUID;
ALTER TABLE expense ADD CONSTRAINT fk_expense_cost_center
    FOREIGN KEY (organization_id, cost_center_id) REFERENCES ledger_cost_center (organization_id, id);

-- A document has at most one live (not reversed) automatic entry, even under concurrent edits.
CREATE UNIQUE INDEX uq_ledger_entry_live_source ON ledger_entry (organization_id, source_type, source_id)
    WHERE source_type IN ('INVOICE', 'BILL', 'PAYMENT', 'EXPENSE', 'BANK_LINE')
      AND reversed_by_id IS NULL AND reversal_of_id IS NULL AND deleted_at IS NULL;
