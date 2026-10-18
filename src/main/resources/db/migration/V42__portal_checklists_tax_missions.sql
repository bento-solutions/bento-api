-- Phase 5: client portal accounts, document checklists with reminders, the Moroccan tax
-- calendar, missions with deliverables and rebillable disbursements, and mobile push tokens.

-- People at a client company who sign in to the portal. Separate from app_user on purpose: a
-- portal account can never hold a staff role, and portal tokens are refused everywhere else.
CREATE TABLE portal_user (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    partner_id          UUID NOT NULL REFERENCES partner(id),
    email               VARCHAR(255) NOT NULL,
    display_name        VARCHAR(120),
    password_hash       VARCHAR(255),
    -- SHA-256 of the one-time invitation (or password reset) token.
    invite_token_hash   VARCHAR(64),
    invite_expires_at   TIMESTAMPTZ,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at       TIMESTAMPTZ,
    -- Sessions issued before this are no longer accepted (password reset).
    password_changed_at TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_portal_user_email ON portal_user (organization_id, lower(email)) WHERE deleted_at IS NULL;
CREATE INDEX idx_portal_user_partner ON portal_user (partner_id);
CREATE UNIQUE INDEX uq_portal_user_invite ON portal_user (invite_token_hash) WHERE invite_token_hash IS NOT NULL;

-- "Documents we need from this client for this period", item by item.
CREATE TABLE document_request (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    partner_id          UUID NOT NULL REFERENCES partner(id),
    title               VARCHAR(160) NOT NULL,
    period              VARCHAR(20),
    due_date            DATE,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    remind_channel      VARCHAR(10) NOT NULL DEFAULT 'EMAIL',
    remind_every_days   INT NOT NULL DEFAULT 3,
    last_reminder_at    TIMESTAMPTZ,
    reminder_count      INT NOT NULL DEFAULT 0,
    note                VARCHAR(1000),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_document_request_partner ON document_request (organization_id, partner_id);
CREATE INDEX idx_document_request_open ON document_request (status) WHERE deleted_at IS NULL;

CREATE TABLE document_request_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    request_id          UUID NOT NULL REFERENCES document_request(id),
    label               VARCHAR(160) NOT NULL,
    required            BOOLEAN NOT NULL DEFAULT TRUE,
    status              VARCHAR(20) NOT NULL DEFAULT 'MISSING',
    file_id             UUID REFERENCES stored_file(id),
    received_at         TIMESTAMPTZ,
    review_note         VARCHAR(500),
    sort_order          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_document_request_item_request ON document_request_item (request_id);

-- Which declarations apply to the company itself (partner_id NULL) or to a client.
CREATE TABLE tax_profile (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    partner_id          UUID REFERENCES partner(id),
    vat_regime          VARCHAR(10) NOT NULL DEFAULT 'MONTHLY',
    corporate_tax       BOOLEAN NOT NULL DEFAULT TRUE,
    fiscal_year_end_month INT NOT NULL DEFAULT 12 CHECK (fiscal_year_end_month BETWEEN 1 AND 12),
    has_employees       BOOLEAN NOT NULL DEFAULT TRUE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_tax_profile ON tax_profile (organization_id, COALESCE(partner_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE deleted_at IS NULL;

CREATE TABLE tax_deadline (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    profile_id          UUID NOT NULL REFERENCES tax_profile(id),
    partner_id          UUID REFERENCES partner(id),
    kind                VARCHAR(20) NOT NULL,
    period_label        VARCHAR(20) NOT NULL,
    period_start        DATE,
    period_end          DATE,
    due_date            DATE NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'TODO',
    done_at             TIMESTAMPTZ,
    done_by             UUID,
    amount              NUMERIC(19, 2),
    note                VARCHAR(500),
    reminded_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_tax_deadline ON tax_deadline (profile_id, kind, period_label) WHERE deleted_at IS NULL;
CREATE INDEX idx_tax_deadline_due ON tax_deadline (organization_id, due_date);

CREATE TABLE mission (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    partner_id          UUID NOT NULL REFERENCES partner(id),
    name                VARCHAR(160) NOT NULL,
    description         VARCHAR(2000),
    status              VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    start_date          DATE,
    due_date            DATE,
    fee_amount          NUMERIC(19, 2),
    assigned_user_id    UUID REFERENCES app_user(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_mission_partner ON mission (organization_id, partner_id);

CREATE TABLE mission_deliverable (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    mission_id          UUID NOT NULL REFERENCES mission(id),
    title               VARCHAR(160) NOT NULL,
    due_date            DATE,
    status              VARCHAR(20) NOT NULL DEFAULT 'TODO',
    file_id             UUID REFERENCES stored_file(id),
    delivered_at        TIMESTAMPTZ,
    visible_to_client   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_mission_deliverable_mission ON mission_deliverable (mission_id);

-- Money spent on a client's behalf (stamp duty, registry fees, travel), billed back at cost.
CREATE TABLE disbursement (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    partner_id          UUID NOT NULL REFERENCES partner(id),
    mission_id          UUID REFERENCES mission(id),
    spent_on            DATE NOT NULL,
    description         VARCHAR(255) NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    vat_rate            NUMERIC(5, 4) NOT NULL DEFAULT 0,
    rebillable          BOOLEAN NOT NULL DEFAULT TRUE,
    invoice_id          UUID REFERENCES invoice(id),
    receipt_file_id     UUID REFERENCES stored_file(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE INDEX idx_disbursement_partner ON disbursement (organization_id, partner_id);
CREATE INDEX idx_disbursement_unbilled ON disbursement (organization_id) WHERE invoice_id IS NULL AND rebillable AND deleted_at IS NULL;

-- Phones that receive push notifications from the mobile app.
CREATE TABLE device_token (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organization(id),
    user_id             UUID NOT NULL,
    platform            VARCHAR(10) NOT NULL,
    token               VARCHAR(512) NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          UUID,
    updated_by          UUID,
    version             BIGINT NOT NULL DEFAULT 0,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_device_token ON device_token (token);
CREATE INDEX idx_device_token_user ON device_token (user_id) WHERE deleted_at IS NULL;
