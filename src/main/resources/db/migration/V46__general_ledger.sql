-- General ledger, phase 1: the chart of accounts (PCGE, seeded per organization on its first
-- ledger call), the journals, analytic cost centers, and double-entry journal entries with their
-- lines. Entries are never edited or deleted: a mistake is corrected by a reversing entry.
--
-- Every reference between two tenant tables is a composite (organization_id, x_id) key, as since
-- V20, so a line can never point at another organization's account, partner or cost center.
-- Entry numbers ("OD-2026-0001") come from ledger_entry_sequence, one counter row per
-- organization, journal and year, incremented inside the posting transaction: the row lock
-- serializes concurrent postings to one journal and a rolled-back posting leaves no gap.

-- Missions become a composite-FK parent (cost centers may point at one).
ALTER TABLE mission ADD CONSTRAINT uq_mission_org_id UNIQUE (organization_id, id);

CREATE TABLE ledger_account (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    -- 4 to 10 digits; the first digit (1..8) is the PCGE class.
    code                VARCHAR(10) NOT NULL CHECK (code ~ '^[1-8][0-9]{3,9}$'),
    name                VARCHAR(160) NOT NULL,
    account_class       SMALLINT NOT NULL CHECK (account_class BETWEEN 1 AND 8),
    kind                VARCHAR(10) NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    -- Seeded PCGE account: can be renamed or deactivated, never deleted.
    is_system           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_account_org_id UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_account_code ON ledger_account (organization_id, code) WHERE deleted_at IS NULL;

CREATE TABLE ledger_journal (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    code                VARCHAR(4) NOT NULL,
    name                VARCHAR(80) NOT NULL,
    -- SALES, PURCHASES, BANK, CASH, MISC, OPENING
    kind                VARCHAR(10) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_journal_org_id UNIQUE (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_journal_code ON ledger_journal (organization_id, code) WHERE deleted_at IS NULL;

CREATE TABLE ledger_cost_center (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    code                VARCHAR(20) NOT NULL,
    name                VARCHAR(160) NOT NULL,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    mission_id          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_cost_center_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_ledger_cost_center_mission
        FOREIGN KEY (organization_id, mission_id) REFERENCES mission (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_cost_center_code ON ledger_cost_center (organization_id, code) WHERE deleted_at IS NULL;

CREATE TABLE ledger_entry (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    journal_id          UUID NOT NULL,
    number              VARCHAR(30) NOT NULL,
    entry_date          DATE NOT NULL,
    description         VARCHAR(255) NOT NULL,
    -- What produced the entry: MANUAL, REVERSAL, later INVOICE, PAYMENT...
    source_type         VARCHAR(30),
    source_id           UUID,
    source_label        VARCHAR(160),
    reversal_of_id      UUID,
    reversed_by_id      UUID,
    total_debit         NUMERIC(19, 2) NOT NULL,
    total_credit        NUMERIC(19, 2) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_ledger_entry_org_id UNIQUE (organization_id, id),
    CONSTRAINT ck_ledger_entry_balanced CHECK (total_debit = total_credit AND total_debit > 0),
    CONSTRAINT fk_ledger_entry_journal
        FOREIGN KEY (organization_id, journal_id) REFERENCES ledger_journal (organization_id, id),
    CONSTRAINT fk_ledger_entry_reversal_of
        FOREIGN KEY (organization_id, reversal_of_id) REFERENCES ledger_entry (organization_id, id),
    CONSTRAINT fk_ledger_entry_reversed_by
        FOREIGN KEY (organization_id, reversed_by_id) REFERENCES ledger_entry (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_entry_number ON ledger_entry (organization_id, number) WHERE deleted_at IS NULL;
-- An entry is reversed at most once, even under concurrent requests.
CREATE UNIQUE INDEX uq_ledger_entry_reversal_of ON ledger_entry (organization_id, reversal_of_id)
    WHERE reversal_of_id IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_ledger_entry_org_date ON ledger_entry (organization_id, entry_date DESC, number DESC);
CREATE INDEX idx_ledger_entry_source ON ledger_entry (organization_id, source_type, source_id);

CREATE TABLE ledger_entry_line (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    entry_id            UUID NOT NULL,
    line_no             INT NOT NULL,
    account_id          UUID NOT NULL,
    partner_id          UUID,
    cost_center_id      UUID,
    label               VARCHAR(255),
    debit               NUMERIC(19, 2) NOT NULL DEFAULT 0,
    credit              NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT ck_ledger_entry_line_amounts CHECK (debit >= 0 AND credit >= 0 AND NOT (debit > 0 AND credit > 0)),
    CONSTRAINT fk_ledger_entry_line_entry
        FOREIGN KEY (organization_id, entry_id) REFERENCES ledger_entry (organization_id, id),
    CONSTRAINT fk_ledger_entry_line_account
        FOREIGN KEY (organization_id, account_id) REFERENCES ledger_account (organization_id, id),
    -- A partner purged after its 30-day grace period must not block the purge job.
    CONSTRAINT fk_ledger_entry_line_partner
        FOREIGN KEY (organization_id, partner_id) REFERENCES partner (organization_id, id) ON DELETE SET NULL (partner_id),
    CONSTRAINT fk_ledger_entry_line_cost_center
        FOREIGN KEY (organization_id, cost_center_id) REFERENCES ledger_cost_center (organization_id, id)
);
CREATE UNIQUE INDEX uq_ledger_entry_line_no ON ledger_entry_line (entry_id, line_no);
CREATE INDEX idx_ledger_entry_line_account ON ledger_entry_line (organization_id, account_id);
CREATE INDEX idx_ledger_entry_line_partner ON ledger_entry_line (organization_id, partner_id) WHERE partner_id IS NOT NULL;
CREATE INDEX idx_ledger_entry_line_cost_center ON ledger_entry_line (organization_id, cost_center_id) WHERE cost_center_id IS NOT NULL;

-- Entry number counters, one per organization, journal and year (of the entry date).
CREATE TABLE ledger_entry_sequence (
    organization_id     UUID NOT NULL REFERENCES organization(id),
    journal_id          UUID NOT NULL,
    year                INT NOT NULL,
    next_value          BIGINT NOT NULL,
    PRIMARY KEY (organization_id, journal_id, year),
    CONSTRAINT fk_ledger_entry_sequence_journal
        FOREIGN KEY (organization_id, journal_id) REFERENCES ledger_journal (organization_id, id)
);
