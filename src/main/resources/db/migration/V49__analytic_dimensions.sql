-- Analytic dimensions: Mission, Deal, Customer and Vendor, beside the cost center.
--
-- Cost centers go back to grouping costs by function or person (management, sales, production...);
-- missions no longer get one. A mission or deal's costs come from these dimensions instead. The
-- default cost centers (DIR, COM, PROD, ADM, IT, MKT) are seeded per organization by the
-- application: ledger_settings.setup_version 7.
--
-- Deals and partners are hard-deleted by the purge job after their grace period: like the partner
-- of an entry line, a purged dimension is set to null rather than blocking the purge.

-- 1. Expenses (and expense-report lines, which are expenses). The Vendor dimension is the existing
--    vendor_partner_id.
ALTER TABLE expense ADD COLUMN mission_id UUID;
ALTER TABLE expense ADD COLUMN deal_id UUID;
ALTER TABLE expense ADD COLUMN customer_id UUID;
ALTER TABLE expense ADD CONSTRAINT fk_expense_mission
    FOREIGN KEY (organization_id, mission_id) REFERENCES mission (organization_id, id) ON DELETE SET NULL (mission_id);
ALTER TABLE expense ADD CONSTRAINT fk_expense_deal
    FOREIGN KEY (organization_id, deal_id) REFERENCES deal (organization_id, id) ON DELETE SET NULL (deal_id);
ALTER TABLE expense ADD CONSTRAINT fk_expense_customer
    FOREIGN KEY (organization_id, customer_id) REFERENCES partner (organization_id, id) ON DELETE SET NULL (customer_id);
CREATE INDEX idx_expense_mission ON expense (organization_id, mission_id) WHERE mission_id IS NOT NULL;
CREATE INDEX idx_expense_deal ON expense (organization_id, deal_id) WHERE deal_id IS NOT NULL;

-- 2. Supplier bill lines live in invoice.lines (jsonb): they carry the keys missionId, dealId,
--    customerId and vendorId, no schema change.

-- 3. Entry lines.
ALTER TABLE ledger_entry_line ADD COLUMN mission_id UUID;
ALTER TABLE ledger_entry_line ADD COLUMN deal_id UUID;
ALTER TABLE ledger_entry_line ADD COLUMN customer_id UUID;
ALTER TABLE ledger_entry_line ADD COLUMN vendor_id UUID;
ALTER TABLE ledger_entry_line ADD CONSTRAINT fk_ledger_entry_line_mission
    FOREIGN KEY (organization_id, mission_id) REFERENCES mission (organization_id, id) ON DELETE SET NULL (mission_id);
ALTER TABLE ledger_entry_line ADD CONSTRAINT fk_ledger_entry_line_deal
    FOREIGN KEY (organization_id, deal_id) REFERENCES deal (organization_id, id) ON DELETE SET NULL (deal_id);
ALTER TABLE ledger_entry_line ADD CONSTRAINT fk_ledger_entry_line_customer
    FOREIGN KEY (organization_id, customer_id) REFERENCES partner (organization_id, id) ON DELETE SET NULL (customer_id);
ALTER TABLE ledger_entry_line ADD CONSTRAINT fk_ledger_entry_line_vendor
    FOREIGN KEY (organization_id, vendor_id) REFERENCES partner (organization_id, id) ON DELETE SET NULL (vendor_id);
CREATE INDEX idx_ledger_entry_line_mission ON ledger_entry_line (organization_id, mission_id) WHERE mission_id IS NOT NULL;
CREATE INDEX idx_ledger_entry_line_deal ON ledger_entry_line (organization_id, deal_id) WHERE deal_id IS NOT NULL;
CREATE INDEX idx_ledger_entry_line_customer ON ledger_entry_line (organization_id, customer_id) WHERE customer_id IS NOT NULL;
CREATE INDEX idx_ledger_entry_line_vendor ON ledger_entry_line (organization_id, vendor_id) WHERE vendor_id IS NOT NULL;
