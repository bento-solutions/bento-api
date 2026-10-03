-- Categories: a single-word, coloured label ("CRMbento", "Orthoflow") that says which product or
-- project a ticket or task belongs to. A ticket carries at most one; its tasks inherit it.

CREATE TABLE category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    name VARCHAR(24) NOT NULL,
    color VARCHAR(12) NOT NULL DEFAULT 'blue',
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID
);

CREATE UNIQUE INDEX idx_category_org_name ON category(organization_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX idx_category_org_deleted ON category(organization_id, deleted_at);

ALTER TABLE ticket ADD COLUMN category_id UUID REFERENCES category(id);
ALTER TABLE task ADD COLUMN category_id UUID REFERENCES category(id);

CREATE INDEX idx_ticket_category ON ticket(organization_id, category_id);
CREATE INDEX idx_task_category ON task(organization_id, category_id);

-- Starter categories for every existing organization; they can be renamed, recoloured or removed.
INSERT INTO category (organization_id, name, color)
SELECT o.id, 'CRMbento', 'blue' FROM organization o
WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.organization_id = o.id AND lower(c.name) = 'crmbento');

INSERT INTO category (organization_id, name, color)
SELECT o.id, 'Orthoflow', 'emerald' FROM organization o
WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.organization_id = o.id AND lower(c.name) = 'orthoflow');
