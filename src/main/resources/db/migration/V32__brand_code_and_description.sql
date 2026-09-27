-- Brands module: short code + description fields required by the Marques ticket

ALTER TABLE brand
    ADD COLUMN IF NOT EXISTS code VARCHAR(20),
    ADD COLUMN IF NOT EXISTS description TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_brand_org_code
    ON brand(organization_id, upper(code))
    WHERE deleted_at IS NULL AND code IS NOT NULL;

-- Backfill a short code on the brands seeded by V31 so existing rows aren't left blank.
UPDATE brand SET code = 'CARS' WHERE code IS NULL AND name = 'BentoCars';
UPDATE brand SET code = 'TRAVEL' WHERE code IS NULL AND name = 'BentoTravel';
UPDATE brand SET code = 'CRM' WHERE code IS NULL AND name = 'CRMbento';
