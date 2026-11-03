-- General ledger, phase 3, part C: mission <-> deal link, delivery notes, purchase accounts per
-- service type.
--
-- The new account (6126) and the SUBCONTRACTING product group are seeded per organization by the
-- application: ledger_settings.setup_version 5.

-- 1. Missions link to the deal they come from.
ALTER TABLE mission ADD COLUMN deal_id UUID;
ALTER TABLE mission ADD CONSTRAINT fk_mission_deal
    FOREIGN KEY (organization_id, deal_id) REFERENCES deal (organization_id, id);
CREATE INDEX idx_mission_deal ON mission (organization_id, deal_id) WHERE deal_id IS NOT NULL;

-- 2. Delivery notes (bons de livraison). They never post.
CREATE TABLE delivery_note (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    number              VARCHAR(30),
    partner_id          UUID NOT NULL,
    deal_id             UUID,
    mission_id          UUID,
    note_date           DATE NOT NULL,
    status              VARCHAR(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'DELIVERED', 'INVOICED', 'CANCELLED')),
    lines               JSONB NOT NULL DEFAULT '[]'::jsonb,
    notes               TEXT,
    invoice_id          UUID,
    delivered_at        TIMESTAMPTZ,
    signed_by           VARCHAR(160),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_delivery_note_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_delivery_note_partner FOREIGN KEY (organization_id, partner_id) REFERENCES partner (organization_id, id),
    CONSTRAINT fk_delivery_note_deal FOREIGN KEY (organization_id, deal_id) REFERENCES deal (organization_id, id),
    CONSTRAINT fk_delivery_note_mission FOREIGN KEY (organization_id, mission_id) REFERENCES mission (organization_id, id),
    CONSTRAINT fk_delivery_note_invoice FOREIGN KEY (organization_id, invoice_id) REFERENCES invoice (organization_id, id)
);
CREATE UNIQUE INDEX uq_delivery_note_number ON delivery_note (organization_id, number) WHERE number IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_delivery_note_deal ON delivery_note (organization_id, deal_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_delivery_note_org_date ON delivery_note (organization_id, note_date DESC);
