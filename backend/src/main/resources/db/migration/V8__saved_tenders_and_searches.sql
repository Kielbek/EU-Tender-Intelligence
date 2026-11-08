-- Stage 4: team watch-list (shared within an organization) and personal saved searches.

CREATE TABLE saved_tenders (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    tender_id        uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    created_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    status           text        NOT NULL DEFAULT 'WATCHING'
                     CHECK (status IN ('WATCHING', 'PREPARING', 'SUBMITTED', 'WON', 'LOST', 'DROPPED')),
    notes            text        CHECK (length(notes) <= 5000),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, tender_id)
);
CREATE INDEX saved_tenders_org_status_idx ON saved_tenders (organization_id, status);

CREATE TABLE saved_searches (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name             text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    filters          jsonb       NOT NULL,
    -- Alerts are delivered from Stage 6 (notifications); stored now so saved searches need no migration later.
    alert_enabled    boolean     NOT NULL DEFAULT false,
    alert_frequency  text        CHECK (alert_frequency IN ('INSTANT', 'DAILY', 'WEEKLY')),
    last_run_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id, name)
);
CREATE INDEX saved_searches_owner_idx ON saved_searches (organization_id, user_id);

-- Dashboard aggregates.
CREATE INDEX tenders_created_idx ON tenders (created_at DESC);
CREATE INDEX tender_versions_published_idx ON tender_versions (published_at DESC);
