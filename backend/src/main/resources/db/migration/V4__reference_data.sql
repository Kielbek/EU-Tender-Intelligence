-- Stage 2: currencies, ECB exchange rates, CPV 2008 vocabulary.

CREATE TABLE currencies (
    code        char(3)  PRIMARY KEY CHECK (code = upper(code)),
    name_en     text     NOT NULL,
    minor_units smallint NOT NULL
);

-- ISO 4217: currencies of EU member states (incl. BGN, used in notices published before Bulgaria's 2026 euro
-- changeover) and other currencies commonly found in TED notices.
INSERT INTO currencies (code, name_en, minor_units) VALUES
    ('EUR', 'Euro', 2),
    ('PLN', 'Polish zloty', 2),
    ('CZK', 'Czech koruna', 2),
    ('DKK', 'Danish krone', 2),
    ('HUF', 'Hungarian forint', 2),
    ('RON', 'Romanian leu', 2),
    ('SEK', 'Swedish krona', 2),
    ('BGN', 'Bulgarian lev', 2),
    ('CHF', 'Swiss franc', 2),
    ('NOK', 'Norwegian krone', 2),
    ('ISK', 'Icelandic krona', 0),
    ('GBP', 'Pound sterling', 2),
    ('USD', 'US dollar', 2);

-- Euro foreign exchange reference rates published by the ECB (units of currency per 1 EUR).
CREATE TABLE exchange_rates (
    currency     char(3)        NOT NULL REFERENCES currencies (code),
    rate_date    date           NOT NULL,
    rate_per_eur numeric(18, 6) NOT NULL CHECK (rate_per_eur > 0),
    source       text           NOT NULL DEFAULT 'ECB',
    created_at   timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (currency, rate_date)
);
CREATE INDEX exchange_rates_date_idx ON exchange_rates (rate_date DESC);

-- CPV 2008 (Common Procurement Vocabulary). Imported from the EU Publications Office (see CpvImportService).
-- Codes are stored as 8 digits, exactly as TED/eForms use them (without the check digit).
CREATE TABLE cpv_codes (
    code         varchar(8)  PRIMARY KEY CHECK (code ~ '^[0-9]{8}$'),
    parent_code  varchar(8)  REFERENCES cpv_codes (code) DEFERRABLE INITIALLY DEFERRED,
    level        smallint    NOT NULL CHECK (level BETWEEN 1 AND 5),
    labels       jsonb       NOT NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cpv_codes_parent_idx ON cpv_codes (parent_code);
CREATE INDEX cpv_codes_labels_idx ON cpv_codes USING gin (labels jsonb_path_ops);
