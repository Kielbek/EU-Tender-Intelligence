package eu.tenderintel.matching;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.matching.MatchingEngine.Profile;
import eu.tenderintel.matching.MatchingEngine.Reason;
import eu.tenderintel.matching.MatchingEngine.Reference;
import eu.tenderintel.matching.MatchingEngine.Result;
import eu.tenderintel.matching.MatchingEngine.Tender;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final Profile BUILDER = new Profile(List.of("45200000"), List.of("PL", "DE"), "PL",
            List.of("WORKS"), new BigDecimal("100000"), new BigDecimal("3000000"),
            List.of(new Reference("Szkoła w Łodzi", "45214200")));

    private static Tender tender(String country, String cpv, String type, String valueEur) {
        return new Tender(country, cpv, List.of(), type, valueEur == null ? null : new BigDecimal(valueEur));
    }

    private static List<String> codes(List<Reason> reasons) {
        return reasons.stream().map(Reason::code).toList();
    }

    @Test
    void perfectFitScoresHundred() {
        Result r = MatchingEngine.score(BUILDER, tender("PL", "45214200", "WORKS", "800000"));
        assertThat(r.score()).isEqualTo(100);
        assertThat(codes(r.met())).containsExactly("CPV_MATCH", "COUNTRY_OF_INTEREST", "VALUE_IN_RANGE",
                "CONTRACT_TYPE", "REFERENCE_SAME_SECTOR");
        assertThat(r.unmet()).isEmpty();
        assertThat(r.missing()).isEmpty();
        assertThat(r.components()).extracting(MatchingEngine.Component::points).containsExactly(45, 20, 15, 10, 10);
    }

    @Test
    void scoreIsSumOfComponentsAndExplainsEveryMiss() {
        Result r = MatchingEngine.score(BUILDER, tender("FR", "45200000", "SERVICES", "9000000"));
        assertThat(r.score()).isEqualTo(45 + 0 + 0 + 0 + 10);
        assertThat(codes(r.unmet())).containsExactly("COUNTRY_OUTSIDE", "VALUE_ABOVE_MAX", "CONTRACT_TYPE_OTHER");
    }

    @Test
    void relatedCpvScoresByCommonPrefix() {
        // Company declares 45230000 (pipelines, roads); tender 45210000 shares "452".
        Profile p = new Profile(List.of("45230000"), List.of(), null, List.of(), null, null, List.of());
        Result r = MatchingEngine.score(p, tender("PL", "45210000", null, null));
        assertThat(r.components().getFirst().points()).isEqualTo(22);
        assertThat(codes(r.met())).containsExactly("CPV_RELATED");
        // Same division only.
        r = MatchingEngine.score(p, tender("PL", "45000000", null, null));
        assertThat(r.components().getFirst().points()).isEqualTo(12);
        // Another sector.
        r = MatchingEngine.score(p, tender("PL", "72000000", null, null));
        assertThat(r.components().getFirst().points()).isZero();
        assertThat(codes(r.unmet())).contains("CPV_OTHER_SECTOR");
    }

    @Test
    void additionalTenderCpvCodesCount() {
        Profile p = new Profile(List.of("72260000"), List.of(), null, List.of(), null, null, List.of());
        Tender t = new Tender("PL", "48000000", List.of("48000000", "72263000"), "SERVICES", null);
        Result r = MatchingEngine.score(p, t);
        assertThat(r.components().getFirst().points()).isEqualTo(45);
        assertThat(r.met().getFirst().params()).containsEntry("tenderCpv", "72263000");
    }

    @Test
    void missingDataIsNeutralAndReportedNeverAMatch() {
        Profile empty = new Profile(List.of(), List.of(), null, List.of(), null, null, List.of());
        Result r = MatchingEngine.score(empty, tender("PL", null, null, null));
        assertThat(r.met()).isEmpty();
        assertThat(codes(r.missing())).containsExactly("PROFILE_NO_CPV", "PROFILE_NO_COUNTRIES",
                "TENDER_VALUE_UNKNOWN", "TENDER_TYPE_UNKNOWN", "PROFILE_NO_REFERENCES");
        assertThat(r.score()).isEqualTo(0 + 10 + 7 + 5 + 0);
    }

    @Test
    void registrationCountryIsUsedWhenNoCountriesDeclared() {
        Profile p = new Profile(List.of("45000000"), List.of(), "PL", List.of(), null, null, List.of());
        assertThat(codes(MatchingEngine.score(p, tender("PL", "45000000", null, null)).met()))
                .contains("HOME_COUNTRY");
        assertThat(codes(MatchingEngine.score(p, tender("DE", "45000000", null, null)).missing()))
                .contains("PROFILE_NO_COUNTRIES");
    }

    @Test
    void valueBelowMinimumScoresLittle() {
        Result r = MatchingEngine.score(BUILDER, tender("PL", "45200000", "WORKS", "5000"));
        assertThat(r.components().get(2).points()).isEqualTo(4);
        assertThat(codes(r.unmet())).containsExactly("VALUE_BELOW_MIN");
    }

    @Test
    void referenceInOtherSectorGivesMinimalExperience() {
        Profile p = new Profile(List.of("72000000"), List.of(), null, List.of(), null, null,
                List.of(new Reference("Droga", "45233000")));
        Result r = MatchingEngine.score(p, tender("PL", "72000000", null, null));
        assertThat(r.components().get(4).points()).isEqualTo(2);
        assertThat(codes(r.unmet())).contains("NO_RELATED_REFERENCE");
    }

    @Test
    void significantPrefixStripsTrailingZeros() {
        assertThat(MatchingEngine.significant("45210000")).isEqualTo("4521");
        assertThat(MatchingEngine.significant("45000000")).isEqualTo("45");
        assertThat(MatchingEngine.significant("40000000")).isEqualTo("40");
        assertThat(MatchingEngine.significant("45214200")).isEqualTo("452142");
    }

    @Test
    void scoreAlwaysWithinBounds() {
        String[] cpvs = {"45000000", "45214200", "72000000", "03000000", null};
        String[] countries = {"PL", "DE", "FR"};
        String[] types = {"WORKS", "SERVICES", null};
        String[] values = {null, "1", "500000", "100000000"};
        for (String c : cpvs) {
            for (String country : countries) {
                for (String type : types) {
                    for (String v : values) {
                        int s = MatchingEngine.score(BUILDER, tender(country, c, type, v)).score();
                        assertThat(s).isBetween(0, 100);
                    }
                }
            }
        }
    }
}
