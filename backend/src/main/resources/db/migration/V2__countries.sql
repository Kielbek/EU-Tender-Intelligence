-- Reference data: EU member states (ISO 3166-1). TED/eForms uses alpha-3 codes, the API and UI use alpha-2.
-- default_currency reflects the official currency as of 2026 (Bulgaria adopted the euro on 2026-01-01).
CREATE TABLE countries (
    code             char(2)     PRIMARY KEY,
    code3            char(3)     NOT NULL UNIQUE,
    name_en          text        NOT NULL,
    is_eu            boolean     NOT NULL DEFAULT false,
    default_currency char(3)     NOT NULL,
    default_language varchar(3)  NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT countries_code_upper CHECK (code = upper(code) AND code3 = upper(code3)),
    CONSTRAINT countries_currency_upper CHECK (default_currency = upper(default_currency))
);

INSERT INTO countries (code, code3, name_en, is_eu, default_currency, default_language) VALUES
    ('AT', 'AUT', 'Austria',        true, 'EUR', 'de'),
    ('BE', 'BEL', 'Belgium',        true, 'EUR', 'nl'),
    ('BG', 'BGR', 'Bulgaria',       true, 'EUR', 'bg'),
    ('HR', 'HRV', 'Croatia',        true, 'EUR', 'hr'),
    ('CY', 'CYP', 'Cyprus',         true, 'EUR', 'el'),
    ('CZ', 'CZE', 'Czechia',        true, 'CZK', 'cs'),
    ('DK', 'DNK', 'Denmark',        true, 'DKK', 'da'),
    ('EE', 'EST', 'Estonia',        true, 'EUR', 'et'),
    ('FI', 'FIN', 'Finland',        true, 'EUR', 'fi'),
    ('FR', 'FRA', 'France',         true, 'EUR', 'fr'),
    ('DE', 'DEU', 'Germany',        true, 'EUR', 'de'),
    ('GR', 'GRC', 'Greece',         true, 'EUR', 'el'),
    ('HU', 'HUN', 'Hungary',        true, 'HUF', 'hu'),
    ('IE', 'IRL', 'Ireland',        true, 'EUR', 'en'),
    ('IT', 'ITA', 'Italy',          true, 'EUR', 'it'),
    ('LV', 'LVA', 'Latvia',         true, 'EUR', 'lv'),
    ('LT', 'LTU', 'Lithuania',      true, 'EUR', 'lt'),
    ('LU', 'LUX', 'Luxembourg',     true, 'EUR', 'fr'),
    ('MT', 'MLT', 'Malta',          true, 'EUR', 'mt'),
    ('NL', 'NLD', 'Netherlands',    true, 'EUR', 'nl'),
    ('PL', 'POL', 'Poland',         true, 'PLN', 'pl'),
    ('PT', 'PRT', 'Portugal',       true, 'EUR', 'pt'),
    ('RO', 'ROU', 'Romania',        true, 'RON', 'ro'),
    ('SK', 'SVK', 'Slovakia',       true, 'EUR', 'sk'),
    ('SI', 'SVN', 'Slovenia',       true, 'EUR', 'sl'),
    ('ES', 'ESP', 'Spain',          true, 'EUR', 'es'),
    ('SE', 'SWE', 'Sweden',         true, 'SEK', 'sv');
