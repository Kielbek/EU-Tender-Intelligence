-- Stage 6: notifications (in-app + e-mail digests) and billing (Stripe subscriptions, invoices, usage counters).

-- ---------------------------------------------------------------------------------------------------------------
-- Notifications
-- ---------------------------------------------------------------------------------------------------------------

-- Per user and organization. A missing row means the defaults defined in NotificationType.
CREATE TABLE notification_preferences (
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    event_type       text        NOT NULL CHECK (event_type IN ('NEW_MATCH', 'WATCHED_CHANGED', 'DEADLINE_APPROACHING')),
    in_app           boolean     NOT NULL,
    email_frequency  text        NOT NULL CHECK (email_frequency IN ('INSTANT', 'DAILY', 'WEEKLY', 'OFF')),
    -- Only for NEW_MATCH: the lowest match score worth a notification.
    min_score        smallint    CHECK (min_score BETWEEN 40 AND 100),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, organization_id, event_type)
);

CREATE TABLE notifications (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    type             text        NOT NULL
                     CHECK (type IN ('NEW_MATCH', 'WATCHED_CHANGED', 'DEADLINE_APPROACHING', 'SAVED_SEARCH')),
    tender_id        uuid        REFERENCES tenders (id) ON DELETE CASCADE,
    saved_search_id  uuid        REFERENCES saved_searches (id) ON DELETE CASCADE,
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    in_app           boolean     NOT NULL,
    -- Decided when the notification is created (preferences at that moment); OFF = no e-mail.
    email_frequency  text        NOT NULL CHECK (email_frequency IN ('INSTANT', 'DAILY', 'WEEKLY', 'OFF')),
    -- The same event never notifies the same person twice (e.g. a tender re-scored after a correction).
    dedup_key        text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    read_at          timestamptz,
    emailed_at       timestamptz,
    UNIQUE (user_id, dedup_key)
);
CREATE INDEX notifications_inbox_idx ON notifications (user_id, organization_id, created_at DESC) WHERE in_app;
CREATE INDEX notifications_unread_idx ON notifications (user_id, organization_id) WHERE in_app AND read_at IS NULL;
CREATE INDEX notifications_email_due_idx ON notifications (email_frequency, user_id, organization_id)
    WHERE emailed_at IS NULL AND email_frequency <> 'OFF';
CREATE INDEX notifications_created_idx ON notifications (created_at);

-- Last digest per person, organization and cadence (daily digests at 07:00 local time, weekly on Mondays).
CREATE TABLE notification_digests (
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    frequency        text        NOT NULL CHECK (frequency IN ('INSTANT', 'DAILY', 'WEEKLY')),
    last_sent_at     timestamptz NOT NULL,
    PRIMARY KEY (user_id, organization_id, frequency)
);

-- Positions of the notification scanner (what has already been looked at).
CREATE TABLE notification_cursors (
    name      text        PRIMARY KEY,
    position  timestamptz NOT NULL
);

-- Saved-search alerts: new tenders since this moment are reported next.
ALTER TABLE saved_searches ADD COLUMN alert_checked_at timestamptz;
-- OFF = in-app notifications only.
ALTER TABLE saved_searches DROP CONSTRAINT saved_searches_alert_frequency_check;
ALTER TABLE saved_searches ADD CONSTRAINT saved_searches_alert_frequency_check
    CHECK (alert_frequency IN ('INSTANT', 'DAILY', 'WEEKLY', 'OFF'));

-- Tender versions created since the last scan (watch-list changes).
CREATE INDEX tender_versions_created_idx ON tender_versions (created_at);
-- Matches computed since the last scan (new matches).
CREATE INDEX tender_matches_computed_idx ON tender_matches (computed_at);

-- E-mails with a one-click unsubscribe link (RFC 8058) carry it as a header.
ALTER TABLE email_outbox ADD COLUMN list_unsubscribe text;

-- ---------------------------------------------------------------------------------------------------------------
-- Billing
-- ---------------------------------------------------------------------------------------------------------------

ALTER TABLE organizations ADD CONSTRAINT organizations_plan_code_check
    CHECK (plan_code IN ('FREE', 'STARTER', 'PRO', 'BUSINESS', 'ENTERPRISE'));
-- ENTERPRISE (and other contracts outside Stripe) is assigned by a platform administrator.
ALTER TABLE organizations ADD COLUMN plan_source text NOT NULL DEFAULT 'DEFAULT'
    CHECK (plan_source IN ('DEFAULT', 'STRIPE', 'MANUAL'));

-- Mirror of Stripe subscriptions, updated only from verified webhooks.
CREATE TABLE subscriptions (
    id                      uuid        PRIMARY KEY,
    organization_id         uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    stripe_subscription_id  text        NOT NULL UNIQUE,
    stripe_customer_id      text        NOT NULL,
    stripe_price_id         text,
    plan_code               text        CHECK (plan_code IN ('STARTER', 'PRO', 'BUSINESS')),
    billing_interval        text        CHECK (billing_interval IN ('MONTH', 'YEAR')),
    -- Stripe statuses: incomplete, incomplete_expired, trialing, active, past_due, canceled, unpaid, paused.
    status                  text        NOT NULL,
    current_period_start    timestamptz,
    current_period_end      timestamptz,
    cancel_at_period_end    boolean     NOT NULL DEFAULT false,
    cancel_at               timestamptz,
    canceled_at             timestamptz,
    trial_end               timestamptz,
    -- Stripe event time of the last applied update: older events delivered late are ignored.
    stripe_updated_at       timestamptz NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX subscriptions_org_idx ON subscriptions (organization_id);

-- Invoices from Stripe (documents themselves stay in Stripe; links are stored).
CREATE TABLE payments (
    id                  uuid           PRIMARY KEY,
    organization_id     uuid           NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    stripe_invoice_id   text           NOT NULL UNIQUE,
    stripe_subscription_id text,
    invoice_number      text,
    status              text           NOT NULL,
    currency            char(3)        NOT NULL,
    amount_due          bigint         NOT NULL,
    amount_paid         bigint         NOT NULL DEFAULT 0,
    tax_amount          bigint,
    hosted_invoice_url  text,
    invoice_pdf_url     text,
    period_start        timestamptz,
    period_end          timestamptz,
    paid_at             timestamptz,
    stripe_updated_at   timestamptz    NOT NULL,
    created_at          timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX payments_org_idx ON payments (organization_id, created_at DESC);

-- Idempotency of webhooks: an event id is processed once.
CREATE TABLE stripe_events (
    event_id      text        PRIMARY KEY,
    type          text        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT now()
);

-- Metered plan limits per calendar month (UTC). Incremented atomically only while below the limit.
CREATE TABLE usage_counters (
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    metric           text        NOT NULL CHECK (metric IN ('EXPORTS')),
    period_start     date        NOT NULL,
    value            int         NOT NULL DEFAULT 0 CHECK (value >= 0),
    PRIMARY KEY (organization_id, metric, period_start)
);
