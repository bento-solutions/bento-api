-- Expense reports (notes de frais): an employee's expenses, posted together against 4438
-- "Personnel - autres créditeurs" and reimbursed from the bank or the cash register.
--
-- The account 4438 and the bank category EXPENSE_REIMBURSEMENT are seeded per organization by the
-- application: ledger_settings.setup_version 6.

CREATE TABLE expense_report (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                 UUID NOT NULL REFERENCES organization(id),
    number                          VARCHAR(30),
    employee_user_id                UUID REFERENCES app_user(id),
    employee_name                   VARCHAR(160) NOT NULL,
    title                           VARCHAR(255),
    period_from                     DATE,
    period_to                       DATE,
    notes                           TEXT,
    status                          VARCHAR(12) NOT NULL DEFAULT 'DRAFT'
                                        CHECK (status IN ('DRAFT', 'POSTED', 'REIMBURSED', 'CANCELLED')),
    posted_at                       TIMESTAMPTZ,
    reimbursed_at                   TIMESTAMPTZ,
    reimbursement_date              DATE,
    reimbursement_method            VARCHAR(20) CHECK (reimbursement_method IN ('BANK_TRANSFER', 'CASH')),
    reimbursement_bank_account_id   UUID REFERENCES bank_account(id),
    cost_center_id                  UUID REFERENCES ledger_cost_center(id),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by                      UUID,
    updated_by                      UUID,
    version                         BIGINT NOT NULL DEFAULT 0,
    deleted_at                      TIMESTAMPTZ,
    CONSTRAINT ck_expense_report_period CHECK (period_from IS NULL OR period_to IS NULL OR period_from <= period_to)
);
CREATE UNIQUE INDEX uq_expense_report_number ON expense_report (organization_id, number)
    WHERE number IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_expense_report_org ON expense_report (organization_id, created_at DESC) WHERE deleted_at IS NULL;

-- The report's lines are expenses.
ALTER TABLE expense ADD COLUMN expense_report_id UUID REFERENCES expense_report(id);
CREATE INDEX idx_expense_report_lines ON expense (expense_report_id) WHERE expense_report_id IS NOT NULL;

-- A report and its reimbursement keep at most one live entry each, like the other documents.
DROP INDEX uq_ledger_entry_live_source;
CREATE UNIQUE INDEX uq_ledger_entry_live_source ON ledger_entry (organization_id, source_type, source_id)
    WHERE source_type IN ('INVOICE', 'BILL', 'PAYMENT', 'EXPENSE', 'BANK_LINE', 'ADVANCE', 'CREDIT_NOTE', 'ADVANCE_APPLY',
                          'VAT_SETTLEMENT', 'STOCK_CHANGE', 'CUTOFF', 'CUTOFF_REVERSAL', 'ASSET', 'CASH', 'YEAR_END',
                          'EXPENSE_REPORT', 'EXPENSE_REPORT_REIMB')
      AND reversed_by_id IS NULL AND reversal_of_id IS NULL AND deleted_at IS NULL;
