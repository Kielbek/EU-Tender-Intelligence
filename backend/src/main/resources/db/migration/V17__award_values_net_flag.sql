-- Award prices in the Polish bulletin (BZP) are gross offer prices (with VAT), while the estimated value of a contract
-- is net (Polish procurement law). TED values are net. A price/estimate ratio is therefore only meaningful for awards
-- published in TED: tender_award_summary gets a flag, and the ratio is computed from flagged rows only.

DROP MATERIALIZED VIEW tender_award_summary;
DROP MATERIALIZED VIEW award_facts;

CREATE MATERIALIZED VIEW award_facts AS
WITH distinct_awards AS (
    SELECT DISTINCT ON (r.tender_id, r.winner_id, r.awarded_value, r.currency)
           r.tender_id, r.winner_id, r.awarded_value_eur, r.tenders_received, r.award_date,
           coalesce(ds.code, 'TED') <> 'PL_EZAMOWIENIA' AS net_value
    FROM tender_results r
    LEFT JOIN tender_sources s ON s.id = r.tender_source_id
    LEFT JOIN data_sources ds ON ds.id = s.data_source_id
    WHERE r.winner_id IS NOT NULL
    ORDER BY r.tender_id, r.winner_id, r.awarded_value, r.currency, r.created_at DESC
)
SELECT d.tender_id,
       d.winner_id,
       sum(d.awarded_value_eur)                                     AS awarded_value_eur,
       count(*)                                                     AS parts,
       count(d.awarded_value_eur)                                   AS valued_parts,
       bool_and(d.net_value)                                        AS net_value,
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

CREATE MATERIALIZED VIEW tender_award_summary AS
SELECT f.tender_id,
       count(DISTINCT f.winner_id)                                  AS winners,
       CASE WHEN bool_and(f.valued_parts = f.parts) THEN sum(f.awarded_value_eur) END AS awarded_value_eur,
       bool_and(f.net_value)                                        AS net_value,
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
