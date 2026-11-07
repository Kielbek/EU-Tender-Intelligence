-- Stage 2: users, authentication tokens, organizations (tenants), membership, invitations, audit log, e-mail outbox.

CREATE TABLE users (
    id                  uuid        PRIMARY KEY,
    email               citext      NOT NULL UNIQUE,
    password_hash       text        NOT NULL,
    full_name           text        NOT NULL,
    locale              varchar(5)  NOT NULL DEFAULT 'pl',
    timezone            text        NOT NULL DEFAULT 'Europe/Warsaw',
    platform_role       text        NOT NULL DEFAULT 'USER' CHECK (platform_role IN ('USER', 'PLATFORM_ADMIN')),
    status              text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LOCKED', 'DELETED')),
    email_verified_at   timestamptz,
    failed_login_count  int         NOT NULL DEFAULT 0,
    locked_until        timestamptz,
    last_login_at       timestamptz,
    terms_accepted_at   timestamptz NOT NULL,
    terms_version       text        NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             int         NOT NULL DEFAULT 0
);

-- Single-use tokens sent by e-mail. Only the SHA-256 hash is stored.
CREATE TABLE user_tokens (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        text        NOT NULL CHECK (type IN ('EMAIL_VERIFY', 'PASSWORD_RESET')),
    token_hash  char(64)    NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX user_tokens_user_type_idx ON user_tokens (user_id, type);

-- Rotating refresh tokens. A family groups all rotations of one login; reuse of a rotated token revokes the family.
CREATE TABLE refresh_tokens (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id    uuid        NOT NULL,
    token_hash   char(64)    NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    replaced_by  uuid,
    user_agent   text,
    ip           inet,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);

CREATE TABLE organizations (
    id                  uuid        PRIMARY KEY,
    name                text        NOT NULL,
    slug                text        NOT NULL UNIQUE,
    country_code        char(2)     REFERENCES countries (code),
    vat_id              text,
    billing_email       citext,
    stripe_customer_id  text        UNIQUE,
    plan_code           text        NOT NULL DEFAULT 'FREE',
    status              text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             int         NOT NULL DEFAULT 0
);

CREATE TABLE organization_members (
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role             text        NOT NULL CHECK (role IN ('COMPANY_ADMIN', 'TEAM_MEMBER')),
    invited_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    joined_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, user_id)
);
CREATE INDEX organization_members_user_idx ON organization_members (user_id);

CREATE TABLE organization_invitations (
    id               uuid        PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    email            citext      NOT NULL,
    role             text        NOT NULL CHECK (role IN ('COMPANY_ADMIN', 'TEAM_MEMBER')),
    token_hash       char(64)    NOT NULL UNIQUE,
    invited_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    expires_at       timestamptz NOT NULL,
    accepted_at      timestamptz,
    revoked_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX organization_invitations_open_uq
    ON organization_invitations (organization_id, email) WHERE accepted_at IS NULL AND revoked_at IS NULL;

-- Append-only audit trail (the application never updates or deletes rows).
CREATE TABLE audit_logs (
    id               uuid        PRIMARY KEY,
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    actor_user_id    uuid,
    organization_id  uuid,
    action           text        NOT NULL,
    target_type      text,
    target_id        text,
    ip               inet,
    user_agent       text,
    correlation_id   text,
    details          jsonb       NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_logs_occurred_idx ON audit_logs (occurred_at DESC);
CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_user_id, occurred_at DESC);
CREATE INDEX audit_logs_org_idx ON audit_logs (organization_id, occurred_at DESC);
CREATE INDEX audit_logs_action_idx ON audit_logs (action, occurred_at DESC);

-- Transactional outbox for e-mails: messages are committed with the business change and delivered asynchronously.
CREATE TABLE email_outbox (
    id           uuid        PRIMARY KEY,
    to_address   citext      NOT NULL,
    template     text        NOT NULL,
    locale       varchar(5)  NOT NULL,
    subject      text        NOT NULL,
    body_text    text,
    status       text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts     int         NOT NULL DEFAULT 0,
    last_error   text,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    sent_at      timestamptz
);
CREATE INDEX email_outbox_pending_idx ON email_outbox (next_attempt_at) WHERE status = 'PENDING';
