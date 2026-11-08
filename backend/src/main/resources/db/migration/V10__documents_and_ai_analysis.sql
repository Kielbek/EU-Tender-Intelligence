-- Stage 5b: tender documents (fetched from public links or uploaded by an organization), AI analyses with citations,
-- AI usage ledger used for plan limits and cost reporting.

-- Document contents, deduplicated by hash. Kept in PostgreSQL (backed up with the database); the storage layer can be
-- moved to S3-compatible storage without changing the API.
CREATE TABLE document_blobs (
    sha256      char(64)    PRIMARY KEY,
    content     bytea       NOT NULL,
    size_bytes  bigint      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE tender_documents
    ADD COLUMN source          text NOT NULL DEFAULT 'PORTAL' CHECK (source IN ('PORTAL', 'UPLOAD')),
    -- NULL = public document (fetched from the notice's public link); otherwise uploaded by and private to this org.
    ADD COLUMN organization_id uuid REFERENCES organizations (id) ON DELETE CASCADE,
    ADD COLUMN uploaded_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN extracted_text  text,
    ADD COLUMN text_chars      int,
    ADD COLUMN error           text CHECK (length(error) <= 500);

ALTER TABLE tender_documents DROP CONSTRAINT tender_documents_extraction_status_check;
ALTER TABLE tender_documents ADD CONSTRAINT tender_documents_extraction_status_check
    CHECK (extraction_status IN ('PENDING', 'DONE', 'FAILED', 'SKIPPED', 'NO_TEXT_LAYER', 'UNSUPPORTED'));

DROP INDEX tender_documents_hash_uq;
CREATE UNIQUE INDEX tender_documents_hash_uq ON tender_documents
    (tender_id, coalesce(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), sha256)
    WHERE sha256 IS NOT NULL;
CREATE INDEX tender_documents_tender_idx ON tender_documents (tender_id, organization_id);

-- One analysis per (tender, scope, language, document set, prompt version). organization_id NULL = analysis of
-- public documents only, shared by every organization; otherwise it includes that organization's uploads.
CREATE TABLE ai_analyses (
    id                 uuid        PRIMARY KEY,
    tender_id          uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    organization_id    uuid        REFERENCES organizations (id) ON DELETE CASCADE,
    language           varchar(3)  NOT NULL,
    document_set_hash  char(64)    NOT NULL,
    prompt_version     int         NOT NULL,
    status             text        NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    model              text,
    result             jsonb,
    error_code         text,
    requested_by       uuid        REFERENCES users (id) ON DELETE SET NULL,
    requested_by_org   uuid        REFERENCES organizations (id) ON DELETE SET NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    started_at         timestamptz,
    completed_at       timestamptz
);
CREATE UNIQUE INDEX ai_analyses_unique_active ON ai_analyses
    (tender_id, coalesce(organization_id, '00000000-0000-0000-0000-000000000000'::uuid), language,
     document_set_hash, prompt_version)
    WHERE status <> 'FAILED';
CREATE INDEX ai_analyses_tender_idx ON ai_analyses (tender_id, created_at DESC);

-- Every call to the model, for plan limits (counts_to_quota) and cost per organization.
CREATE TABLE ai_usage (
    id                     uuid           PRIMARY KEY,
    organization_id        uuid           NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    user_id                uuid           REFERENCES users (id) ON DELETE SET NULL,
    feature                text           NOT NULL CHECK (feature IN ('DOCUMENT_ANALYSIS')),
    analysis_id            uuid           REFERENCES ai_analyses (id) ON DELETE SET NULL,
    model                  text,
    input_tokens           bigint         NOT NULL DEFAULT 0,
    output_tokens          bigint         NOT NULL DEFAULT 0,
    cache_read_tokens      bigint         NOT NULL DEFAULT 0,
    cache_creation_tokens  bigint         NOT NULL DEFAULT 0,
    cost_usd               numeric(12, 6) NOT NULL DEFAULT 0,
    counts_to_quota        boolean        NOT NULL DEFAULT true,
    created_at             timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX ai_usage_org_month_idx ON ai_usage (organization_id, created_at);
