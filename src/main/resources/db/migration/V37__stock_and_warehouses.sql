-- Stock: warehouses, on-hand quantity per product and warehouse, and every movement that changed it.

ALTER TABLE product ADD COLUMN track_stock BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE product ADD COLUMN reorder_level NUMERIC(15, 3);

CREATE TABLE warehouse (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    name                VARCHAR(120) NOT NULL,
    code                VARCHAR(30),
    address             TEXT,
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_warehouse_name ON warehouse (organization_id, lower(name)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_warehouse_default ON warehouse (organization_id) WHERE is_default AND deleted_at IS NULL;

-- Written only through an atomic upsert (StockService), so concurrent movements cannot lose updates.
CREATE TABLE stock_level (
    organization_id     UUID NOT NULL REFERENCES organization(id),
    product_id          UUID NOT NULL REFERENCES product(id),
    warehouse_id        UUID NOT NULL REFERENCES warehouse(id),
    quantity            NUMERIC(15, 3) NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, product_id, warehouse_id)
);

CREATE TABLE stock_movement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    product_id          UUID NOT NULL REFERENCES product(id),
    warehouse_id        UUID NOT NULL REFERENCES warehouse(id),
    type                VARCHAR(20) NOT NULL,
    -- Signed: positive into the warehouse, negative out of it.
    quantity            NUMERIC(15, 3) NOT NULL,
    unit_cost           NUMERIC(15, 2),
    balance_after       NUMERIC(15, 3) NOT NULL,
    reference_type      VARCHAR(30),
    reference_id        UUID,
    reference_label     VARCHAR(100),
    note                TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_stock_movement_product ON stock_movement (organization_id, product_id, created_at DESC);
CREATE INDEX idx_stock_movement_reference ON stock_movement (organization_id, reference_type, reference_id);

-- When stock goes out automatically: DEAL_WON, INVOICE_SENT or MANUAL. Chosen at onboarding.
CREATE TABLE stock_settings (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    issue_trigger       VARCHAR(20) NOT NULL DEFAULT 'INVOICE_SENT',
    receive_on_delivery BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ,
    CONSTRAINT uq_stock_settings_org UNIQUE (organization_id)
);
