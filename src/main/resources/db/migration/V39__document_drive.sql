-- Document drive: folders, tags, search and expiring share links on top of stored_file, plus the
-- content hash used to skip duplicate documents arriving by email or WhatsApp.
CREATE TABLE drive_folder (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    parent_id           UUID REFERENCES drive_folder(id),
    name                VARCHAR(120) NOT NULL,
    partner_id          UUID REFERENCES partner(id),
    -- Folders the system files documents into (vendor invoices, bank statements, a partner's
    -- folder). They cannot be renamed or deleted.
    system_key          VARCHAR(80),
    -- Authority needed to see the folder's files, on top of FILES_READ (financial documents).
    required_authority  VARCHAR(40),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_drive_folder_name ON drive_folder (organization_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name))
    WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_drive_folder_system_key ON drive_folder (organization_id, system_key)
    WHERE system_key IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE stored_file
    ADD COLUMN folder_id   UUID REFERENCES drive_folder(id),
    ADD COLUMN tags        TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN description VARCHAR(500),
    ADD COLUMN partner_id  UUID REFERENCES partner(id),
    ADD COLUMN source      VARCHAR(20) NOT NULL DEFAULT 'UPLOAD',
    ADD COLUMN sha256      VARCHAR(64);
CREATE INDEX idx_stored_file_folder ON stored_file (organization_id, folder_id);
CREATE INDEX idx_stored_file_partner ON stored_file (organization_id, partner_id);
CREATE INDEX idx_stored_file_sha ON stored_file (organization_id, sha256);
CREATE INDEX idx_stored_file_tags ON stored_file USING GIN (tags);

CREATE TABLE file_share_link (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    file_id             UUID NOT NULL REFERENCES stored_file(id) ON DELETE CASCADE,
    token               VARCHAR(64) NOT NULL UNIQUE,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    download_count      INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_file_share_link_file ON file_share_link (organization_id, file_id);
