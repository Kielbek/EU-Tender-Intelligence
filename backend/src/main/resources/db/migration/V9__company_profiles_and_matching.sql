-- Stage 5a: company profile (private to the organization) and explainable tender matching.

CREATE TABLE company_profiles (
    id                      uuid           PRIMARY KEY,
    organization_id         uuid           NOT NULL UNIQUE REFERENCES organizations (id) ON DELETE CASCADE,
    name                    text           NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    registration_country    char(2)        REFERENCES countries (code),
    industry                text           CHECK (length(industry) <= 200),
    description             text           CHECK (length(description) <= 5000),
    website                 text           CHECK (length(website) <= 300),
    countries_of_interest   char(2)[]      NOT NULL DEFAULT '{}',
    contract_types          text[]         NOT NULL DEFAULT '{}',
    company_size            text           CHECK (company_size IN ('MICRO', 'SMALL', 'MEDIUM', 'LARGE')),
    employees               int            CHECK (employees >= 0),
    annual_revenue          numeric(18, 2) CHECK (annual_revenue >= 0),
    revenue_currency        char(3)        CHECK (revenue_currency ~ '^[A-Z]{3}$'),
    years_of_experience     int            CHECK (years_of_experience BETWEEN 0 AND 300),
    min_contract_value_eur  numeric(18, 2) CHECK (min_contract_value_eur >= 0),
    max_contract_value_eur  numeric(18, 2) CHECK (max_contract_value_eur >= 0),
    technical_resources     text           CHECK (length(technical_resources) <= 5000),
    staff_resources         text           CHECK (length(staff_resources) <= 5000),
    financial_capacity      text           CHECK (length(financial_capacity) <= 5000),
    created_at              timestamptz    NOT NULL DEFAULT now(),
    updated_at              timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT company_profiles_value_range CHECK (
        min_contract_value_eur IS NULL OR max_contract_value_eur IS NULL
        OR min_contract_value_eur <= max_contract_value_eur)
);

CREATE TABLE company_cpv (
    company_profile_id  uuid       NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    cpv_code            varchar(8) NOT NULL CHECK (cpv_code ~ '^[0-9]{8}$'),
    PRIMARY KEY (company_profile_id, cpv_code)
);

CREATE TABLE company_certifications (
    id                  uuid        PRIMARY KEY,
    company_profile_id  uuid        NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    name                text        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    issuer              text        CHECK (length(issuer) <= 200),
    valid_until         date,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX company_certifications_profile_idx ON company_certifications (company_profile_id);

CREATE TABLE company_references (
    id                  uuid           PRIMARY KEY,
    company_profile_id  uuid           NOT NULL REFERENCES company_profiles (id) ON DELETE CASCADE,
    title               text           NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    client              text           CHECK (length(client) <= 200),
    country_code        char(2)        REFERENCES countries (code),
    value               numeric(18, 2) CHECK (value >= 0),
    currency            char(3)        CHECK (currency ~ '^[A-Z]{3}$'),
    year_completed      int            CHECK (year_completed BETWEEN 1950 AND 2100),
    cpv_code            varchar(8)     CHECK (cpv_code ~ '^[0-9]{8}$'),
    description         text           CHECK (length(description) <= 2000),
    created_at          timestamptz    NOT NULL DEFAULT now()
);
CREATE INDEX company_references_profile_idx ON company_references (company_profile_id);

-- One row per (organization, open tender) with a score worth showing. Recomputed when the profile changes and when
-- a tender is ingested or changed. The score is a fit indicator, never a probability of winning.
CREATE TABLE tender_matches (
    organization_id  uuid        NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    tender_id        uuid        NOT NULL REFERENCES tenders (id) ON DELETE CASCADE,
    score            smallint    NOT NULL CHECK (score BETWEEN 0 AND 100),
    breakdown        jsonb       NOT NULL,
    engine_version   int         NOT NULL,
    computed_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, tender_id)
);
CREATE INDEX tender_matches_org_score_idx ON tender_matches (organization_id, score DESC);
CREATE INDEX tender_matches_tender_idx ON tender_matches (tender_id);

-- Candidate pre-selection by CPV division.
CREATE INDEX tenders_open_cpv_division_idx ON tenders (left(main_cpv, 2)) WHERE status = 'OPEN';
