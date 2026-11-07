-- Stage 2: public tender data model and ingestion bookkeeping. Populated from Stage 3 (TED ingestion).

-- unaccent() is only STABLE; an IMMUTABLE wrapper with an explicit dictionary is required for generated columns/indexes.
CREATE FUNCTION immutable_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    AS $$ SELECT public.unaccent('public.unaccent'::regdictionary, $1) $$;

CREATE TABLE data_sources (
    id                 uuid        PRIMARY KEY,
    code               text        NOT NULL UNIQUE,
    name               text        NOT NULL,
    country_code       char(2)     REFERENCES countries (code),
    adapter_type       text        NOT NULL,
    status             text        NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'ERROR', 'PENDING_CONFIGURATION')),
    homepage_url       text,
    terms_url          text,
    terms_verified_at  timestamptz,
    schedule_cron      text,
    config             jsonb       NOT NULL DEFAULT '{}'::jsonb,
    notes              text,
    last_success_at    timestamptz,
    last_error_at      timestamptz,
    last_error         text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ingestion_jobs (
    id              uuid        PRIMARY KEY,
    data_source_id  uuid        NOT NULL REFERENCES data_sources (id),
    type            text        NOT NULL CHECK (type IN ('INCREMENTAL', 'BACKFILL', 'DOCUMENTS', 'REFERENCE')),
    status          text        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PARTIAL')),
    window_from     timestamptz,
    window_to       timestamptz,
    cursor          text,
    fetched_count   int         NOT NULL DEFAULT 0,
    created_count   int         NOT NULL DEFAULT 0,
    updated_count   int         NOT NULL DEFAULT 0,
    skipped_count   int         NOT NULL DEFAULT 0,
    error_count     int         NOT NULL DEFAULT 0,
    errors          jsonb       NOT NULL DEFAULT '[]'::jsonb,
    triggered_by    text        NOT NULL,
    started_at      timestamptz,
    finished_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ingestion_jobs_source_idx ON ingestion_jobs (data_source_id, created_at DESC);

CREATE TABLE raw_notices (
    id              uuid        PRIMARY KEY,
    data_source_id  uuid        NOT NULL REFERENCES data_sources (id),
    external_id     text        NOT NULL,
    payload_hash    char(64)    NOT NULL,
    payload         jsonb,
    storage_key     text,
    fetched_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (data_source_id, external_id, payload_hash)
);

CREATE TABLE contracting_authorities (
    id               uuid        PRIMARY KEY,
    name             text        NOT NULL,
    normalized_name  text        NOT NULL,
    national_id      text,
    country_code     char(2)     REFERENCES countries (code),
    nuts_code        varchar(5),
    city             text,
    address          text,
    website          text,
    authority_type   text,
    main_activity    text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX contracting_authorities_national_id_uq
    ON contracting_authorities (country_code, national_id) WHERE national_id IS NOT NULL;
CREATE INDEX contracting_authorities_name_trgm_idx ON contracting_authorities USING gin (normalized_name gin_trgm_ops);

CREATE TABLE economic_operators (
    id               uuid        PRIMARY KEY,
    name             text        NOT NULL,
    normalized_name  text        NOT NULL,
    national_id      text,
    country_code     char(2)     REFERENCES countries (code),
    is_sme           boolean,
    created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX economic_operators_national_id_uq
    ON economic_operators (country_code, national_id) WHERE national_id IS NOT NULL;
CREATE INDEX economic_operators_name_trgm_idx ON economic_operators USING gin (normalized_name gin_trgm_ops);

CREATE TABLE tenders (
    id                        uuid           PRIMARY KEY,
    procedure_key             text           NOT NULL UNIQUE,
    title                     text           NOT NULL,
    title_lang                varchar(3),
    description               text,
    country_code              char(2)        NOT NULL REFERENCES countries (code),
    nuts_code                 varchar(5),
    city                      text,
    contracting_authority_id  uuid           REFERENCES contracting_authorities (id),
    reference_number          text,
    contract_type             text           CHECK (contract_type IN ('WORKS', 'SUPPLIES', 'SERVICES', 'MIXED')),
    procedure_type            text,
    main_cpv                  varchar(8)     REFERENCES cpv_codes (code),
    estimated_value           numeric(18, 2) CHECK (estimated_value >= 0),
    currency                  char(3)        REFERENCES currencies (code),
    estimated_value_eur       numeric(18, 2) CHECK (estimated_value_eur >= 0),
    published_at              timestamptz    NOT NULL,
    submission_deadline       timestamptz,
    submission_deadline_tz    text,
    opening_at                timestamptz,
    status                    text           NOT NULL DEFAULT 'UNKNOWN'
                              CHECK (status IN ('PLANNED', 'OPEN', 'CLOSED', 'AWARDED', 'CANCELLED', 'UNKNOWN')),
    notice_language           varchar(3),
    original_url              text,
    documents_url             text,
    participation_conditions  jsonb,
    award_criteria            jsonb,
    current_version_no        int            NOT NULL DEFAULT 1,
    last_changed_at           timestamptz    NOT NULL DEFAULT now(),
    search_vector             tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(title, ''))), 'A') ||
        setweight(to_tsvector('simple', immutable_unaccent(coalesce(description, ''))), 'B')
    ) STORED,
    embedding                 vector(1536),
    created_at                timestamptz    NOT NULL DEFAULT now(),
    updated_at                timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT tenders_value_currency CHECK (estimated_value IS NULL OR currency IS NOT NULL)
);
CREATE INDEX tenders_country_status_deadline_idx ON tenders (country_code, status, submission_deadline);
CREATE INDEX tenders_published_idx ON tenders (published_at DESC);
CREATE INDEX tenders_main_cpv_idx ON tenders (main_cpv varchar_pattern_ops);
CREATE INDEX tenders_value_eur_idx ON tenders (estimated_value_eur);
CREATE INDEX tenders_open_deadline_idx ON tenders (submission_deadline) WHERE status = 'OPEN';
CREATE INDEX tenders_authority_idx ON tenders (contracting_authority_id);
CREATE INDEX tenders_search_idx ON tenders USING gin (search_vector);
CREATE INDEX tenders_embedding_idx ON tenders USING hnsw (embedding vector_cosine_ops);

CREATE TABLE tender_sources (
    id              uuid        PRIMARY KEY,
    tender_id       uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    data_source_id  uuid        NOT NULL REFERENCES data_sources (id),
    external_id     text        NOT NULL,
    external_url    text,
    notice_type     text,
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (data_source_id, external_id)
);
CREATE INDEX tender_sources_tender_idx ON tender_sources (tender_id);

CREATE TABLE tender_versions (
    id                uuid        PRIMARY KEY,
    tender_id         uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    version_no        int         NOT NULL,
    tender_source_id  uuid        REFERENCES tender_sources (id),
    notice_type       text,
    change_kind       text        NOT NULL
                      CHECK (change_kind IN ('INITIAL', 'CORRIGENDUM', 'MODIFICATION', 'CANCELLATION', 'AWARD')),
    snapshot          jsonb       NOT NULL,
    diff              jsonb       NOT NULL DEFAULT '[]'::jsonb,
    published_at      timestamptz NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tender_id, version_no)
);

CREATE TABLE tender_lots (
    id                   uuid           PRIMARY KEY,
    tender_id            uuid           NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    lot_number           text           NOT NULL,
    title                text,
    description          text,
    main_cpv             varchar(8)     REFERENCES cpv_codes (code),
    estimated_value      numeric(18, 2),
    currency             char(3)        REFERENCES currencies (code),
    submission_deadline  timestamptz,
    UNIQUE (tender_id, lot_number)
);

CREATE TABLE tender_cpv (
    tender_id  uuid       NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    cpv_code   varchar(8) NOT NULL REFERENCES cpv_codes (code),
    is_main    boolean    NOT NULL DEFAULT false,
    PRIMARY KEY (tender_id, cpv_code)
);
CREATE INDEX tender_cpv_code_idx ON tender_cpv (cpv_code varchar_pattern_ops);

CREATE TABLE tender_documents (
    id                 uuid        PRIMARY KEY,
    tender_id          uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    source_url         text,
    file_name          text,
    mime_type          text,
    size_bytes         bigint,
    sha256             char(64),
    storage_key        text,
    language           varchar(3),
    fetch_status       text        NOT NULL DEFAULT 'PENDING'
                       CHECK (fetch_status IN ('PENDING', 'FETCHED', 'FAILED', 'NOT_ACCESSIBLE', 'REQUIRES_LOGIN')),
    extraction_status  text        NOT NULL DEFAULT 'PENDING'
                       CHECK (extraction_status IN ('PENDING', 'DONE', 'FAILED', 'SKIPPED')),
    ocr_used           boolean     NOT NULL DEFAULT false,
    page_count         int,
    version_no         int         NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX tender_documents_hash_uq ON tender_documents (tender_id, sha256) WHERE sha256 IS NOT NULL;

CREATE TABLE tender_document_chunks (
    id            uuid        PRIMARY KEY,
    document_id   uuid        NOT NULL REFERENCES tender_documents (id) ON DELETE CASCADE,
    chunk_index   int         NOT NULL,
    page_from     int,
    page_to       int,
    section_path  text,
    content       text        NOT NULL,
    content_lang  varchar(3),
    token_count   int,
    embedding     vector(1536),
    tsv           tsvector GENERATED ALWAYS AS (to_tsvector('simple', immutable_unaccent(content))) STORED,
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX tender_document_chunks_tsv_idx ON tender_document_chunks USING gin (tsv);
CREATE INDEX tender_document_chunks_embedding_idx ON tender_document_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE tender_requirements (
    id              uuid        PRIMARY KEY,
    tender_id       uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    category        text        NOT NULL,
    text            text        NOT NULL,
    normalized      jsonb,
    source_kind     text        NOT NULL CHECK (source_kind IN ('NOTICE', 'DOCUMENT_AI')),
    document_id     uuid        REFERENCES tender_documents (id) ON DELETE CASCADE,
    page            int,
    section         text,
    quote           text,
    quote_verified  boolean     NOT NULL DEFAULT false,
    analysis_id     uuid,
    language        varchar(3),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX tender_requirements_tender_idx ON tender_requirements (tender_id, category);

CREATE TABLE tender_results (
    id                uuid           PRIMARY KEY,
    tender_id         uuid           NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    lot_id            uuid           REFERENCES tender_lots (id) ON DELETE CASCADE,
    winner_id         uuid           REFERENCES economic_operators (id),
    awarded_value     numeric(18, 2),
    currency          char(3)        REFERENCES currencies (code),
    awarded_value_eur numeric(18, 2),
    tenders_received  int,
    lowest_offer      numeric(18, 2),
    highest_offer     numeric(18, 2),
    award_date        date,
    tender_source_id  uuid           REFERENCES tender_sources (id),
    created_at        timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX tender_results_tender_idx ON tender_results (tender_id);
CREATE INDEX tender_results_winner_idx ON tender_results (winner_id);
