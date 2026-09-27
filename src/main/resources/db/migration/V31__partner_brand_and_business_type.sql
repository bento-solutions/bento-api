-- Partners - rattacher chaque lead a une Marque + filtrer par marque et type d'activite

-- 1. Brand referential (BentoCars, BentoTravel, CRMbento...)
CREATE TABLE IF NOT EXISTS brand (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    name VARCHAR(100) NOT NULL,
    color_hex VARCHAR(7),
    is_default BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_brand_org_name ON brand(organization_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_brand_org_deleted ON brand(organization_id, deleted_at);

-- 2. Business type referential (agence de location, agence de voyage, concessionnaire moto...)
CREATE TABLE IF NOT EXISTS business_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organization(id),
    name VARCHAR(100) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by UUID,
    updated_by UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_business_type_org_name ON business_type(organization_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_business_type_org_deleted ON business_type(organization_id, deleted_at);

-- 3. Partner attribution columns
ALTER TABLE partner
    ADD COLUMN IF NOT EXISTS brand_id UUID REFERENCES brand(id),
    ADD COLUMN IF NOT EXISTS business_type_id UUID REFERENCES business_type(id);

CREATE INDEX IF NOT EXISTS idx_partner_brand ON partner(organization_id, brand_id);
CREATE INDEX IF NOT EXISTS idx_partner_business_type ON partner(organization_id, business_type_id);

-- 4. Seed the referentials for every organization that existed before this ticket
INSERT INTO brand (organization_id, name, color_hex, is_default)
SELECT o.id, v.name, v.color_hex, v.is_default
FROM organization o
CROSS JOIN (VALUES
    ('BentoCars', '#2563EB', true),
    ('BentoTravel', '#059669', false),
    ('CRMbento', '#7C3AED', false)
) AS v(name, color_hex, is_default)
ON CONFLICT DO NOTHING;

INSERT INTO business_type (organization_id, name)
SELECT o.id, v.name
FROM organization o
CROSS JOIN (VALUES
    ('Agence de location'),
    ('Agence de voyage'),
    ('Concessionnaire moto'),
    ('Import/export'),
    ('Centre de formation')
) AS v(name)
ON CONFLICT DO NOTHING;

-- 5. Data migration: reprise des leads existants a partir de company.business_type
-- 'Location de voitures' -> BentoCars / Agence de location
UPDATE partner p
SET business_type_id = bt.id
FROM business_type bt
WHERE p.business_type_id IS NULL
  AND bt.organization_id = p.organization_id
  AND bt.name = 'Agence de location'
  AND p.company ->> 'business_type' ILIKE '%location%voiture%';

UPDATE partner p
SET brand_id = b.id
FROM brand b
WHERE p.brand_id IS NULL
  AND b.organization_id = p.organization_id
  AND b.name = 'BentoCars'
  AND p.company ->> 'business_type' ILIKE '%location%voiture%';

-- 'Agence de voyage' -> BentoTravel / Agence de voyage
UPDATE partner p
SET business_type_id = bt.id
FROM business_type bt
WHERE p.business_type_id IS NULL
  AND bt.organization_id = p.organization_id
  AND bt.name = 'Agence de voyage'
  AND p.company ->> 'business_type' ILIKE '%voyage%';

UPDATE partner p
SET brand_id = b.id
FROM brand b
WHERE p.brand_id IS NULL
  AND b.organization_id = p.organization_id
  AND b.name = 'BentoTravel'
  AND p.company ->> 'business_type' ILIKE '%voyage%';

-- 'Concessionnaire moto' / 'moto' -> CRMbento (no dedicated brand for it), Concessionnaire moto
UPDATE partner p
SET business_type_id = bt.id
FROM business_type bt
WHERE p.business_type_id IS NULL
  AND bt.organization_id = p.organization_id
  AND bt.name = 'Concessionnaire moto'
  AND p.company ->> 'business_type' ILIKE '%moto%';

-- 6. Every remaining lead (no match above, or created before company.business_type existed)
-- falls back to the organization's default brand so brand attribution has no gaps going forward.
UPDATE partner p
SET brand_id = b.id
FROM brand b
WHERE p.brand_id IS NULL
  AND b.organization_id = p.organization_id
  AND b.is_default = true;
