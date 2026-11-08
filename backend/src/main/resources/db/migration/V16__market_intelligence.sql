-- Market intelligence: who wins, at what price, with how many competitors.
--
-- tender_results keeps one row per winner and published notice. The same award is often published more than once
-- (TED and BZP, corrections, contract modifications), so statistics read award_facts, where every
-- (tender, winner, awarded value) is counted once. A winner of several lots with different prices keeps all of them.

CREATE INDEX tender_results_created_idx ON tender_results (created_at);

CREATE MATERIALIZED VIEW award_facts AS
WITH distinct_awards AS (
    SELECT DISTINCT ON (r.tender_id, r.winner_id, r.awarded_value, r.currency)
           r.tender_id, r.winner_id, r.awarded_value_eur, r.tenders_received, r.award_date
    FROM tender_results r
    WHERE r.winner_id IS NOT NULL
    ORDER BY r.tender_id, r.winner_id, r.awarded_value, r.currency, r.created_at DESC
)
SELECT d.tender_id,
       d.winner_id,
       sum(d.awarded_value_eur)                                     AS awarded_value_eur,
       count(*)                                                     AS parts,
       count(d.awarded_value_eur)                                   AS valued_parts,
       max(d.tenders_received)                                      AS tenders_received,
       coalesce(max(d.award_date), t.last_changed_at::date)         AS award_date,
       t.country_code,
       t.main_cpv,
       t.contracting_authority_id                                   AS authority_id
FROM distinct_awards d
JOIN tenders t ON t.id = d.tender_id
GROUP BY d.tender_id, d.winner_id, t.last_changed_at, t.country_code, t.main_cpv, t.contracting_authority_id;

CREATE UNIQUE INDEX award_facts_pk ON award_facts (tender_id, winner_id);
CREATE INDEX award_facts_winner_idx ON award_facts (winner_id, award_date DESC);
CREATE INDEX award_facts_authority_idx ON award_facts (authority_id, award_date DESC);
CREATE INDEX award_facts_market_idx ON award_facts (country_code, main_cpv varchar_pattern_ops, award_date DESC);

-- One row per awarded tender: total awarded value, competition, and the ratio to the estimate (when both are known).
CREATE MATERIALIZED VIEW tender_award_summary AS
SELECT f.tender_id,
       count(DISTINCT f.winner_id)                                  AS winners,
       CASE WHEN bool_and(f.valued_parts = f.parts) THEN sum(f.awarded_value_eur) END AS awarded_value_eur,
       max(f.tenders_received)                                      AS tenders_received,
       max(f.award_date)                                            AS award_date,
       t.estimated_value_eur,
       t.country_code,
       t.main_cpv,
       t.contracting_authority_id                                   AS authority_id
FROM award_facts f
JOIN tenders t ON t.id = f.tender_id
GROUP BY f.tender_id, t.estimated_value_eur, t.country_code, t.main_cpv, t.contracting_authority_id;

CREATE UNIQUE INDEX tender_award_summary_pk ON tender_award_summary (tender_id);
CREATE INDEX tender_award_summary_market_idx
    ON tender_award_summary (country_code, main_cpv varchar_pattern_ops, award_date DESC);
CREATE INDEX tender_award_summary_authority_idx ON tender_award_summary (authority_id, award_date DESC);

-- Competitors an organization follows (shared by the team, like the watch-list).
CREATE TABLE watched_companies (
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    operator_id      uuid        NOT NULL REFERENCES economic_operators (id) ON DELETE CASCADE,
    added_by         uuid        REFERENCES users (id) ON DELETE SET NULL,
    note             text        CHECK (length(note) <= 500),
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, operator_id)
);
CREATE INDEX watched_companies_operator_idx ON watched_companies (operator_id);

-- New notification type: a watched competitor won a contract.
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN ('NEW_MATCH', 'WATCHED_CHANGED', 'DEADLINE_APPROACHING', 'SAVED_SEARCH', 'COMPETITOR_WON'));
ALTER TABLE notifications ADD COLUMN operator_id uuid REFERENCES economic_operators (id) ON DELETE CASCADE;
ALTER TABLE notification_preferences DROP CONSTRAINT notification_preferences_event_type_check;
ALTER TABLE notification_preferences ADD CONSTRAINT notification_preferences_event_type_check
    CHECK (event_type IN ('NEW_MATCH', 'WATCHED_CHANGED', 'DEADLINE_APPROACHING', 'COMPETITOR_WON'));
