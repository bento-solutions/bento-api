-- V25: Production hardening, 30-day soft delete, optimistic locking, and financial integrity

-- 1. Hardening Payment Foreign Key (prevent cascading delete of payments when invoices are purged/deleted)
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT constraint_name
        FROM information_schema.table_constraints
        WHERE table_name = 'payment'
          AND constraint_type = 'FOREIGN KEY'
          AND constraint_name LIKE '%invoice%'
    ) LOOP
        EXECUTE 'ALTER TABLE payment DROP CONSTRAINT IF EXISTS ' || quote_ident(r.constraint_name);
    END LOOP;
END $$;

ALTER TABLE payment
    ADD CONSTRAINT fk_payment_invoice
    FOREIGN KEY (invoice_id) REFERENCES invoice(id) ON DELETE RESTRICT;

-- 2. Rename existing version column on automation_rule so optimistic locking 'version' does not collide
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'automation_rule' AND column_name = 'version'
    ) THEN
        ALTER TABLE automation_rule RENAME COLUMN version TO rule_version;
    END IF;
END $$;

-- 3. Add deleted_at and version across tenant entities
DO $$
DECLARE
    tbl text;
    tables text[] := ARRAY[
        'partner', 'deal', 'deal_activity', 'deal_order_line', 'invoice',
        'payment', 'purchase_order', 'proposal', 'proposal_template',
        'ticket', 'task', 'campaign', 'campaign_recipient', 'automation_rule',
        'app_user', 'team', 'crm_group', 'group_meeting', 'group_message',
        'lead_contact', 'lead_activity', 'lead_status_history', 'customer_card',
        'stored_file', 'wa_account', 'wa_conversation', 'wa_message', 'wa_followup',
        'notification', 'user_invitation', 'tag'
    ];
BEGIN
    FOREACH tbl IN ARRAY tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = tbl) THEN
            EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ', tbl);
            EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0', tbl);
            EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I(organization_id, deleted_at)', 'idx_' || tbl || '_org_deleted', tbl);
        END IF;
    END LOOP;
END $$;

-- 4. Purchase Order Itemization & Financial Totals
ALTER TABLE purchase_order
    ADD COLUMN IF NOT EXISTS order_number VARCHAR(64),
    ADD COLUMN IF NOT EXISTS order_date DATE,
    ADD COLUMN IF NOT EXISTS lines JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS subtotal NUMERIC(19,2),
    ADD COLUMN IF NOT EXISTS tax NUMERIC(19,2),
    ADD COLUMN IF NOT EXISTS total NUMERIC(19,2),
    ADD COLUMN IF NOT EXISTS notes TEXT;

-- 5. Product Catalog Table
CREATE TABLE IF NOT EXISTS product (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    sku VARCHAR(64) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100),
    unit_price NUMERIC(19,2) NOT NULL DEFAULT 0.00,
    cost_price NUMERIC(19,2) DEFAULT 0.00,
    tax_rate NUMERIC(5,4) NOT NULL DEFAULT 0.2000,
    is_active BOOLEAN NOT NULL DEFAULT true,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_product_org_sku ON product(organization_id, sku) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_product_org_deleted ON product(organization_id, deleted_at);

-- 6. Ticket Comments Table
CREATE TABLE IF NOT EXISTS ticket_comment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    ticket_id UUID NOT NULL REFERENCES ticket(id) ON DELETE CASCADE,
    author_id UUID,
    author_name VARCHAR(128) NOT NULL,
    author_role VARCHAR(64),
    content TEXT NOT NULL,
    is_internal BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID
);

CREATE INDEX IF NOT EXISTS idx_ticket_comment_ticket ON ticket_comment(ticket_id);
CREATE INDEX IF NOT EXISTS idx_ticket_comment_org_deleted ON ticket_comment(organization_id, deleted_at);
