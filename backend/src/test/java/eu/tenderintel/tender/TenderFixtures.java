package eu.tenderintel.tender;

import org.springframework.jdbc.core.JdbcTemplate;

/** Synthetic tender fixtures (clearly fake identifiers) shared by integration tests. */
public final class TenderFixtures {

    public static final String T1 = "00000000-0000-7000-8000-00000000a001";
    public static final String T2 = "00000000-0000-7000-8000-00000000a002";
    public static final String T3 = "00000000-0000-7000-8000-00000000a003";

    private TenderFixtures() {
    }

    public static void insert(JdbcTemplate jdbc) {
        jdbc.update("delete from tenders where procedure_key like 'test:%'");
        jdbc.update("""
                insert into cpv_codes (code, parent_code, level, labels) values
                  ('45000000', null, 1, '{"en":"Construction work","pl":"Roboty budowlane"}'),
                  ('45200000', '45000000', 2, '{"en":"Works for complete or part construction"}'),
                  ('72000000', null, 1, '{"en":"IT services","pl":"Usługi informatyczne"}')
                on conflict do nothing
                """);
        jdbc.update("""
                insert into contracting_authorities (id, name, normalized_name, country_code, city) values
                  ('00000000-0000-7000-8000-00000000c001', 'Stadt Testhausen', 'stadt testhausen', 'DE', 'Testhausen'),
                  ('00000000-0000-7000-8000-00000000c002', 'Gmina Łódź Testowa', 'gmina lodz testowa', 'PL', 'Łódź')
                on conflict do nothing
                """);
        jdbc.update("""
                insert into tenders (id, procedure_key, title, description, country_code, city, contracting_authority_id,
                    contract_type, procedure_type, main_cpv, estimated_value, currency, estimated_value_eur,
                    published_at, submission_deadline, status, notice_language)
                values
                  (?::uuid, 'test:1', 'Neubau einer Grundschule', 'Bauarbeiten für Schulgebäude', 'DE', 'Testhausen',
                   '00000000-0000-7000-8000-00000000c001', 'WORKS', 'OPEN', '45200000', 2500000, 'EUR', 2500000,
                   '2026-11-01T08:00:00Z', '2026-12-10T11:00:00Z', 'OPEN', 'de'),
                  (?::uuid, 'test:2', 'Przebudowa drogi gminnej', 'Roboty drogowe, nawierzchnia', 'PL', 'Łódź',
                   '00000000-0000-7000-8000-00000000c002', 'WORKS', 'OPEN', '45000000', 1000000, 'PLN', 227583.38,
                   '2026-11-03T08:00:00Z', '2026-11-20T09:00:00Z', 'OPEN', 'pl'),
                  (?::uuid, 'test:3', 'Wdrożenie systemu ERP', 'Usługi informatyczne i licencje', 'PL', 'Łódź',
                   '00000000-0000-7000-8000-00000000c002', 'SERVICES', 'OPEN', '72000000', null, null, null,
                   '2026-11-05T08:00:00Z', '2026-12-01T09:00:00Z', 'CANCELLED', 'pl')
                """, T1, T2, T3);
        jdbc.update("insert into tender_cpv (tender_id, cpv_code, is_main) values (?::uuid, '45200000', true)", T1);
        jdbc.update("""
                insert into tender_versions (id, tender_id, version_no, change_kind, snapshot, diff, published_at)
                values ('00000000-0000-7000-8000-00000000b001', ?::uuid, 1, 'COMPETITION', '{}', '[]', '2026-11-01T08:00:00Z'),
                       ('00000000-0000-7000-8000-00000000b002', ?::uuid, 2, 'CHANGE', '{}',
                        '[{"field":"submissionDeadline","old":"2026-12-03T11:00:00Z","new":"2026-12-10T11:00:00Z"}]',
                        '2026-11-04T08:00:00Z')
                """, T1, T1);
        jdbc.update("""
                insert into tender_lots (id, tender_id, lot_number, title, estimated_value, currency, submission_deadline)
                values ('00000000-0000-7000-8000-00000000d001', ?::uuid, 'LOT-0001', 'Rohbau', 1500000, 'EUR',
                        '2026-12-10T11:00:00Z')
                """, T1);
        jdbc.update("""
                insert into economic_operators (id, name, normalized_name, country_code)
                values ('00000000-0000-7000-8000-00000000e001', 'Testbau GmbH', 'testbau gmbh', 'DE')
                on conflict do nothing
                """);
        jdbc.update("""
                insert into tender_results (id, tender_id, winner_id, awarded_value, currency, awarded_value_eur,
                    tenders_received, award_date)
                values ('00000000-0000-7000-8000-00000000f001', ?::uuid, '00000000-0000-7000-8000-00000000e001',
                        2400000, 'EUR', 2400000, 4, '2026-11-02')
                """, T1);
    }
}
