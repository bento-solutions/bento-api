-- General ledger, phase 3, wave A: VAT determination, advance invoices and advances, bank lines
-- posted on validation, credit notes.
--
-- The new posting-setup values (VAT mentions, VAT codes of the product groups, the TRANSPORT
-- group, the EXPORT business group's exemption) are seeded per organization by the application,
-- like the rest of the setup: ledger_settings.setup_version tells which seeds an organization has.

-- 1. VAT determination

ALTER TABLE ledger_vat_setup ADD COLUMN mention VARCHAR(500);

ALTER TABLE ledger_product_group ADD COLUMN vat_code VARCHAR(20);

ALTER TABLE ledger_business_group ADD COLUMN vat_rule VARCHAR(10) NOT NULL DEFAULT 'STANDARD';
ALTER TABLE ledger_business_group ADD CONSTRAINT chk_ledger_business_group_vat_rule
    CHECK (vat_rule IN ('STANDARD', 'EXEMPT'));
ALTER TABLE ledger_business_group ADD COLUMN exempt_vat_code VARCHAR(20);

-- Null means the product group's code.
ALTER TABLE product ADD COLUMN vat_code VARCHAR(20);

-- Null with a VAT amount means TVA20, as before.
ALTER TABLE expense ADD COLUMN vat_code VARCHAR(20);

-- 2 is the phase 2 setup; the application brings each organization to the current version.
ALTER TABLE ledger_settings ADD COLUMN setup_version INT NOT NULL DEFAULT 2;

-- 2. Advance invoices, credit notes and advance payments

ALTER TABLE invoice ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'INVOICE';
ALTER TABLE invoice ADD CONSTRAINT chk_invoice_kind CHECK (kind IN ('INVOICE', 'ADVANCE', 'CREDIT_NOTE'));
ALTER TABLE invoice ADD COLUMN source_proposal_id UUID;
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_source_proposal
    FOREIGN KEY (organization_id, source_proposal_id) REFERENCES proposal (organization_id, id);
ALTER TABLE invoice ADD COLUMN credited_invoice_id UUID;
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_credited_invoice
    FOREIGN KEY (organization_id, credited_invoice_id) REFERENCES invoice (organization_id, id);
ALTER TABLE invoice ADD COLUMN credit_reason TEXT;
-- [{advanceInvoiceId, base, vat, vatCode, number, date}] on a final invoice.
ALTER TABLE invoice ADD COLUMN advance_deductions JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_invoice_credited ON invoice (organization_id, credited_invoice_id) WHERE credited_invoice_id IS NOT NULL;
CREATE INDEX idx_invoice_kind ON invoice (organization_id, kind) WHERE kind <> 'INVOICE';
CREATE INDEX idx_invoice_source_proposal ON invoice (organization_id, source_proposal_id) WHERE source_proposal_id IS NOT NULL;

-- A payment received or made before any invoice (4421 / 3411), applied to an invoice later.
ALTER TABLE payment ADD COLUMN advance BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment ADD COLUMN advance_applied_on DATE;

-- 3. Bank lines post once a person validates them

ALTER TABLE ledger_settings ADD COLUMN bank_posting_mode VARCHAR(20) NOT NULL DEFAULT 'ON_VALIDATION';
ALTER TABLE ledger_settings ADD CONSTRAINT chk_ledger_settings_bank_posting_mode
    CHECK (bank_posting_mode IN ('ON_VALIDATION', 'ON_IMPORT'));

ALTER TABLE bank_transaction ADD COLUMN validated_at TIMESTAMPTZ;
ALTER TABLE bank_transaction ADD COLUMN validated_by UUID;
-- Everything already there has posted: it counts as validated, so nothing changes in the books.
UPDATE bank_transaction SET validated_at = created_at WHERE validated_at IS NULL;
CREATE INDEX idx_bank_transaction_to_validate ON bank_transaction (organization_id, bank_account_id, booking_date)
    WHERE validated_at IS NULL AND deleted_at IS NULL;

-- The new automatic sources keep at most one live entry per document too.
DROP INDEX uq_ledger_entry_live_source;
CREATE UNIQUE INDEX uq_ledger_entry_live_source ON ledger_entry (organization_id, source_type, source_id)
    WHERE source_type IN ('INVOICE', 'BILL', 'PAYMENT', 'EXPENSE', 'BANK_LINE', 'ADVANCE', 'CREDIT_NOTE', 'ADVANCE_APPLY')
      AND reversed_by_id IS NULL AND reversal_of_id IS NULL AND deleted_at IS NULL;
