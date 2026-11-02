-- General ledger, phase 3, wave B: lock date and year-end, VAT summary and settlement, stock change,
-- cut-off assistant, fixed assets, cash register, recurring supplier bills.
--
-- The new accounts (34558, 3427, 3458, 4458, 3491, 4491, the fixed-asset accounts, 6513, 7513) and
-- the fixed-asset categories are seeded per organization by the application, like the rest of the
-- setup: ledger_settings.setup_version 4 brings the categories.

-- 5. Settings, lock date and years

ALTER TABLE ledger_settings ADD COLUMN lock_date DATE;
ALTER TABLE ledger_settings ADD COLUMN vat_regime VARCHAR(12) NOT NULL DEFAULT 'ENCAISSEMENT';
ALTER TABLE ledger_settings ADD CONSTRAINT chk_ledger_settings_vat_regime CHECK (vat_regime IN ('ENCAISSEMENT', 'DEBIT'));
ALTER TABLE ledger_settings ADD COLUMN vat_period VARCHAR(10) NOT NULL DEFAULT 'MONTHLY';
ALTER TABLE ledger_settings ADD CONSTRAINT chk_ledger_settings_vat_period CHECK (vat_period IN ('MONTHLY', 'QUARTERLY'));

-- A closed financial year: its result entry (OD, last day) and its carry-forward (AN, next first day).
CREATE TABLE ledger_fiscal_year (
    organization_id     UUID NOT NULL REFERENCES organization(id),
    year                INT NOT NULL,
    status              VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    start_date          DATE NOT NULL,
    end_date            DATE NOT NULL,
    closed_at           TIMESTAMPTZ,
    closed_by           UUID,
    result_entry_id     UUID,
    opening_entry_id    UUID,
    -- The lock date before the close moved it, put back on reopen.
    previous_lock_date  DATE,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, year),
    CONSTRAINT fk_ledger_fiscal_year_result FOREIGN KEY (organization_id, result_entry_id) REFERENCES ledger_entry (organization_id, id),
    CONSTRAINT fk_ledger_fiscal_year_opening FOREIGN KEY (organization_id, opening_entry_id) REFERENCES ledger_entry (organization_id, id)
);

-- Who changed the lock date, closed or reopened a year.
CREATE TABLE ledger_audit_log (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    user_id             UUID,
    action              VARCHAR(40) NOT NULL,
    details             TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_ledger_audit_log_org ON ledger_audit_log (organization_id, created_at DESC);

-- 7. Stock change: the valuation each posting recorded, so the next one knows its opening value.
CREATE TABLE ledger_stock_change (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    change_date         DATE NOT NULL,
    opening             NUMERIC(19, 2) NOT NULL,
    closing             NUMERIC(19, 2) NOT NULL,
    entry_id            UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT fk_ledger_stock_change_entry FOREIGN KEY (organization_id, entry_id) REFERENCES ledger_entry (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_stock_change_date ON ledger_stock_change (organization_id, change_date) WHERE deleted_at IS NULL;

-- 9. Fixed assets

CREATE TABLE ledger_asset_category (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id         UUID NOT NULL REFERENCES organization(id),
    code                    VARCHAR(30) NOT NULL,
    name                    VARCHAR(160) NOT NULL,
    asset_account_id        UUID NOT NULL,
    depreciation_account_id UUID NOT NULL,
    expense_account_id      UUID NOT NULL,
    default_life_months     INT NOT NULL CHECK (default_life_months > 0),
    sort_order              INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_ledger_asset_category_code UNIQUE (organization_id, code),
    CONSTRAINT fk_ledger_asset_category_asset FOREIGN KEY (organization_id, asset_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_asset_category_depr FOREIGN KEY (organization_id, depreciation_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_asset_category_expense FOREIGN KEY (organization_id, expense_account_id) REFERENCES ledger_account (organization_id, id)
);

CREATE TABLE ledger_fixed_asset (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id             UUID NOT NULL REFERENCES organization(id),
    code                        VARCHAR(30) NOT NULL,
    name                        VARCHAR(160) NOT NULL,
    category                    VARCHAR(30) NOT NULL,
    acquisition_date            DATE NOT NULL,
    start_date                  DATE NOT NULL,
    acquisition_cost            NUMERIC(19, 2) NOT NULL CHECK (acquisition_cost > 0),
    residual_value              NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (residual_value >= 0),
    method                      VARCHAR(10) NOT NULL CHECK (method IN ('LINEAR', 'DECLINING')),
    useful_life_months          INT NOT NULL CHECK (useful_life_months > 0),
    coefficient                 NUMERIC(4, 2),
    asset_account_id            UUID NOT NULL,
    depreciation_account_id     UUID NOT NULL,
    expense_account_id          UUID NOT NULL,
    status                      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'FULLY_DEPRECIATED', 'DISPOSED')),
    disposed_at                 DATE,
    disposal_price              NUMERIC(19, 2),
    supplier_invoice_id         UUID,
    supplier_invoice_line_index INT,
    paid_from                   VARCHAR(20) NOT NULL DEFAULT 'BANK' CHECK (paid_from IN ('BANK', 'SUSPENSE', 'SUPPLIER_BILL')),
    cost_center_id              UUID,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                  UUID,
    updated_by                  UUID,
    version                     BIGINT NOT NULL DEFAULT 0,
    deleted_at                  TIMESTAMPTZ,
    CONSTRAINT uq_ledger_fixed_asset_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_ledger_fixed_asset_asset FOREIGN KEY (organization_id, asset_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_fixed_asset_depr FOREIGN KEY (organization_id, depreciation_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_fixed_asset_expense FOREIGN KEY (organization_id, expense_account_id) REFERENCES ledger_account (organization_id, id),
    CONSTRAINT fk_ledger_fixed_asset_invoice FOREIGN KEY (organization_id, supplier_invoice_id) REFERENCES invoice (organization_id, id),
    CONSTRAINT fk_ledger_fixed_asset_cost_center FOREIGN KEY (organization_id, cost_center_id) REFERENCES ledger_cost_center (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_fixed_asset_code ON ledger_fixed_asset (organization_id, code) WHERE deleted_at IS NULL;

-- One row per asset and month posted by a depreciation run.
CREATE TABLE ledger_depreciation (
    organization_id     UUID NOT NULL REFERENCES organization(id),
    asset_id            UUID NOT NULL,
    year                INT NOT NULL,
    month               INT NOT NULL CHECK (month BETWEEN 1 AND 12),
    amount              NUMERIC(19, 2) NOT NULL,
    entry_id            UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (asset_id, year, month),
    CONSTRAINT fk_ledger_depreciation_asset FOREIGN KEY (organization_id, asset_id) REFERENCES ledger_fixed_asset (organization_id, id),
    CONSTRAINT fk_ledger_depreciation_entry FOREIGN KEY (organization_id, entry_id) REFERENCES ledger_entry (organization_id, id)
);

-- 10. Cash register movements entered by hand (the register itself is read from the 5161 lines).
CREATE TABLE cash_movement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    movement_date       DATE NOT NULL,
    kind                VARCHAR(3) NOT NULL CHECK (kind IN ('IN', 'OUT')),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    label               VARCHAR(255) NOT NULL,
    category            VARCHAR(60),
    partner_id          UUID,
    cost_center_id      UUID,
    entry_id            UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT fk_cash_movement_partner FOREIGN KEY (organization_id, partner_id) REFERENCES partner (organization_id, id) ON DELETE SET NULL (partner_id),
    CONSTRAINT fk_cash_movement_cost_center FOREIGN KEY (organization_id, cost_center_id) REFERENCES ledger_cost_center (organization_id, id),
    CONSTRAINT fk_cash_movement_entry FOREIGN KEY (organization_id, entry_id) REFERENCES ledger_entry (organization_id, id)
);
CREATE INDEX idx_cash_movement_org_date ON cash_movement (organization_id, movement_date DESC);

-- Recurring supplier bills.
ALTER TABLE recurring_invoice ADD COLUMN type VARCHAR(10) NOT NULL DEFAULT 'CUSTOMER';
ALTER TABLE recurring_invoice ADD CONSTRAINT chk_recurring_invoice_type CHECK (type IN ('CUSTOMER', 'VENDOR'));

-- The closing sources keep at most one live entry per source id too (depreciation entries are
-- tracked per asset and month in ledger_depreciation instead).
DROP INDEX uq_ledger_entry_live_source;
CREATE UNIQUE INDEX uq_ledger_entry_live_source ON ledger_entry (organization_id, source_type, source_id)
    WHERE source_type IN ('INVOICE', 'BILL', 'PAYMENT', 'EXPENSE', 'BANK_LINE', 'ADVANCE', 'CREDIT_NOTE', 'ADVANCE_APPLY',
                          'VAT_SETTLEMENT', 'STOCK_CHANGE', 'CUTOFF', 'CUTOFF_REVERSAL', 'ASSET', 'CASH', 'YEAR_END')
      AND reversed_by_id IS NULL AND reversal_of_id IS NULL AND deleted_at IS NULL;
