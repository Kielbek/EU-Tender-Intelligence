-- Stage 3: adjustments driven by real TED data (verified on a full publication day, 2026-11-06).

-- Buyers outside the EU appear in TED (EEA, candidate countries, EU-funded external aid). Countries are added on
-- demand from the ISO 3166 list in the JDK, without a guessed currency or language.
ALTER TABLE countries ALTER COLUMN default_currency DROP NOT NULL;
ALTER TABLE countries ALTER COLUMN default_language DROP NOT NULL;

-- Source data is authoritative: an unknown currency (e.g. COP, MDL) or a CPV code missing from the local vocabulary
-- must not block ingestion. Formats are still enforced.
ALTER TABLE tenders DROP CONSTRAINT tenders_currency_fkey;
ALTER TABLE tenders DROP CONSTRAINT tenders_main_cpv_fkey;
ALTER TABLE tenders ADD CONSTRAINT tenders_currency_format CHECK (currency ~ '^[A-Z]{3}$');
ALTER TABLE tenders ADD CONSTRAINT tenders_main_cpv_format CHECK (main_cpv ~ '^[0-9]{8}$');
ALTER TABLE tender_lots DROP CONSTRAINT tender_lots_main_cpv_fkey;
ALTER TABLE tender_lots DROP CONSTRAINT tender_lots_currency_fkey;
ALTER TABLE tender_cpv DROP CONSTRAINT tender_cpv_cpv_code_fkey;
ALTER TABLE tender_cpv ADD CONSTRAINT tender_cpv_code_format CHECK (cpv_code ~ '^[0-9]{8}$');
ALTER TABLE tender_results DROP CONSTRAINT tender_results_currency_fkey;

-- Ordering of notices: TED publication numbers ("771348-2026") are sequential per year. Canonical tender state is
-- taken from the newest notice even when notices are ingested out of order (backfill).
ALTER TABLE tenders ADD COLUMN latest_order_key bigint NOT NULL DEFAULT 0;
ALTER TABLE tender_versions ADD COLUMN order_key bigint NOT NULL DEFAULT 0;
ALTER TABLE tender_versions ADD COLUMN external_id text;
CREATE INDEX tender_versions_order_idx ON tender_versions (tender_id, order_key DESC);

-- eForms has no separate corrigendum notice type: a change is a notice of the same form with change-* fields.
ALTER TABLE tender_versions DROP CONSTRAINT tender_versions_change_kind_check;
ALTER TABLE tender_versions ADD CONSTRAINT tender_versions_change_kind_check CHECK (change_kind IN
    ('PRIOR_INFORMATION', 'COMPETITION', 'CHANGE', 'CANCELLATION', 'AWARD', 'MODIFICATION', 'OTHER'));

ALTER TABLE tender_results ADD COLUMN winner_size text;
ALTER TABLE tender_results ADD COLUMN lot_identifier text;
CREATE INDEX tender_lots_deadline_idx ON tender_lots (tender_id, submission_deadline);

-- Fast skip of already ingested notices and per-procedure lookups.
CREATE INDEX tender_sources_external_idx ON tender_sources (data_source_id, external_id);

UPDATE data_sources
SET status = 'ACTIVE',
    adapter_type = 'TED_SEARCH_API_V3',
    homepage_url = 'https://ted.europa.eu',
    schedule_cron = '0 20 6-21 * * MON-SAT',
    notes = 'Search API v3 (anonymous). Reuse of notices for analysis is described in the TED developer docs '
         || '(docs.ted.europa.eu); formal legal notice still to be verified by the operator.',
    updated_at = now()
WHERE code = 'TED';

-- At most one active job per data source, across all application instances.
CREATE UNIQUE INDEX ingestion_jobs_one_active_per_source
    ON ingestion_jobs (data_source_id) WHERE status IN ('QUEUED', 'RUNNING');
