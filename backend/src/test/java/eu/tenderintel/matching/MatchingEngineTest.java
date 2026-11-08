package eu.tenderintel.matching;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.matching.MatchingEngine.GeoPoint;
import eu.tenderintel.matching.MatchingEngine.Profile;
import eu.tenderintel.matching.MatchingEngine.Reason;
import eu.tenderintel.matching.MatchingEngine.Reference;
import eu.tenderintel.matching.MatchingEngine.Result;
import eu.tenderintel.matching.MatchingEngine.Tender;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    // NUTS 3 label points (Eurostat GISCO): Kraków city, Tarnów subregion, Gdańsk, Szczecin, Görlitz (DE).
    static final GeoPoint KRAKOW = new GeoPoint(50.0610, 19.9370);
    static final GeoPoint TARNOW = new GeoPoint(50.0121, 20.9858);
    static final GeoPoint GDANSK = new GeoPoint(54.3520, 18.6466);
    static final GeoPoint SZCZECIN = new GeoPoint(53.4285, 14.5528);
    static final GeoPoint GORLITZ = new GeoPoint(51.1528, 14.9873);

    private static final Profile BUILDER = Profile.basic(List.of("45200000"), List.of("PL", "DE"), "PL",
            List.of("WORKS"), new BigDecimal("100000"), new BigDecimal("3000000"),
            List.of(new Reference("Szkoła w Łodzi", "45214200")));

    /** A road builder based in Kraków working within 150 km. */
    private static Profile local(boolean strict) {
        return new Profile(List.of("45233000"), List.of(), "PL", List.of("WORKS"), null, null, List.of(), "PL213",
                KRAKOW, 150, strict, List.of(), true, List.of(), List.of(), List.of());
    }

    private static Tender tender(String country, String cpv, String type, String valueEur) {
        return Tender.basic(country, cpv, List.of(), type, valueEur == null ? null : new BigDecimal(valueEur));
    }

    private static Tender at(String nuts, GeoPoint place, String type, String text) {
        return new Tender("PL", "45233120", List.of(), type, null, nuts, place, text);
    }

    private static List<String> codes(List<Reason> reasons) {
        return reasons.stream().map(Reason::code).toList();
    }

    @Test
    void perfectFitScoresHighAndEveryComponentIsExplained() {
        Result r = MatchingEngine.score(BUILDER, tender("PL", "45214200", "WORKS", "800000"));
        // No base region: country-level location (3/5), no keywords declared (neutral half).
        assertThat(r.components()).extracting(MatchingEngine.Component::points).containsExactly(35, 15, 15, 10, 5, 5);
        assertThat(r.score()).isEqualTo(85);
        assertThat(codes(r.met())).containsExactly("CPV_MATCH", "COUNTRY_OF_INTEREST", "VALUE_IN_RANGE",
                "CONTRACT_TYPE", "REFERENCE_SAME_SECTOR");
        assertThat(codes(r.missing())).containsExactly("PROFILE_NO_BASE", "TENDER_NO_LOCATION");
    }

    @Test
    void distanceFromTheBaseDecidesTheLocationScore() {
        // Tarnów ~75 km: within the nearer half of 150 km → full points.
        Result near = MatchingEngine.score(local(false), at("PL217", TARNOW, "WORKS", null));
        assertThat(near.distanceKm()).isBetween(70, 80);
        assertThat(near.components().get(1).points()).isEqualTo(25);
        assertThat(codes(near.met())).contains("WITHIN_DISTANCE");

        // Gdańsk ~480 km: beyond the radius → no location points but still listed (PREFER mode).
        Result far = MatchingEngine.score(local(false), at("PL633", GDANSK, "WORKS", null));
        assertThat(far.distanceKm()).isBetween(460, 500);
        assertThat(far.components().get(1).points()).isZero();
        assertThat(far.excluded()).isFalse();
        assertThat(codes(far.unmet())).contains("BEYOND_DISTANCE");
        assertThat(far.score()).isLessThan(near.score() - 20);

        // STRICT: outside the working area is not a match at all.
        Result strict = MatchingEngine.score(local(true), at("PL633", GDANSK, "WORKS", null));
        assertThat(strict.excluded()).isTrue();
        assertThat(strict.score()).isZero();
        assertThat(codes(strict.unmet()).getFirst()).isEqualTo("BEYOND_DISTANCE");
    }

    @Test
    void sameSubregionIsSaidPlainly() {
        Result r = MatchingEngine.score(local(false), at("PL213", KRAKOW, "WORKS", null));
        assertThat(r.distanceKm()).isZero();
        assertThat(codes(r.met())).contains("SAME_AREA");
    }

    @Test
    void scoreDecreasesTowardsTheEdgeOfTheRadius() {
        Profile p = new Profile(List.of("45233000"), List.of(), "PL", List.of("WORKS"), null, null, List.of(), "PL424",
                SZCZECIN, 400, false, List.of(), true, List.of(), List.of(), List.of());
        int nearEdge = MatchingEngine.score(p, at("PL633", GDANSK, "WORKS", null)).components().get(1).points();
        assertThat(nearEdge).isBetween(10, 22); // ~290 km of 400: past the nearer half, above the 40 % floor
    }

    @Test
    void distanceWorksAcrossBordersForCountriesOfInterest() {
        Profile p = new Profile(List.of("45233000"), List.of("PL", "DE"), "PL", List.of("WORKS"), null, null,
                List.of(), "PL424", SZCZECIN, 300, true, List.of(), true, List.of(), List.of(), List.of());
        Result r = MatchingEngine.score(p, new Tender("DE", "45233120", List.of(), "WORKS", null, "DED2D", GORLITZ,
                null));
        assertThat(r.excluded()).isFalse();
        assertThat(codes(r.met())).contains("WITHIN_DISTANCE");
        // A country outside the declared ones is excluded in STRICT mode.
        Result fr = MatchingEngine.score(p, new Tender("FR", "45233120", List.of(), "WORKS", null, null, null, null));
        assertThat(fr.excluded()).isTrue();
        assertThat(codes(fr.unmet())).contains("COUNTRY_OUTSIDE");
    }

    @Test
    void chosenRegionsAndNationwideSuppliesIgnoreTheRadius() {
        Profile p = new Profile(List.of("45233000", "44000000"), List.of(), "PL", List.of("WORKS", "SUPPLIES"), null,
                null, List.of(), "PL213", KRAKOW, 100, true, List.of("PL63"), true, List.of(), List.of(), List.of());
        Result pomorskie = MatchingEngine.score(p, at("PL633", GDANSK, "WORKS", null));
        assertThat(pomorskie.excluded()).isFalse();
        assertThat(codes(pomorskie.met())).contains("IN_SELECTED_REGION");
        Result supplies = MatchingEngine.score(p, new Tender("PL", "44000000", List.of(), "SUPPLIES", null, "PL424",
                SZCZECIN, null));
        assertThat(supplies.excluded()).isFalse();
        assertThat(codes(supplies.met())).contains("SUPPLIES_NATIONWIDE");
    }

    @Test
    void keywordsAreWordStemsWithoutDiacritics() {
        Profile p = new Profile(List.of("45233000"), List.of(), "PL", List.of("WORKS"), null, null, List.of(), null,
                null, null, false, List.of(), true, List.of("nawierzchni", "drog"), List.of("most"), List.of());
        Result hit = MatchingEngine.score(p, at(null, null, "WORKS", "Remont NAWIERZCHNI ulicy Łąkowej"));
        assertThat(hit.components().get(4).points()).isEqualTo(10);
        assertThat(hit.met().stream().filter(m -> m.code().equals("KEYWORD_MATCH")).findFirst().orElseThrow()
                .params()).containsEntry("keywords", "nawierzchni");
        // "drog" is a stem of "drogowe" but not a part of "podroze".
        assertThat(MatchingEngine.score(p, at(null, null, "WORKS", "Usługi podróży służbowych")).components().get(4)
                .points()).isZero();
        assertThat(MatchingEngine.score(p, at(null, null, "WORKS", "Roboty drogowe")).components().get(4).points())
                .isEqualTo(10);
        // An excluded word rules the tender out ("Most" → "mostu").
        Result bridge = MatchingEngine.score(p, at(null, null, "WORKS", "Przebudowa mostu na rzece Wiśle"));
        assertThat(bridge.excluded()).isTrue();
        assertThat(codes(bridge.unmet()).getFirst()).isEqualTo("EXCLUDED_KEYWORD");
    }

    @Test
    void excludedCpvRulesOutTheTenderEvenInsideTheSector() {
        Profile p = new Profile(List.of("45000000"), List.of(), "PL", List.of(), null, null, List.of(), null, null,
                null, false, List.of(), true, List.of(), List.of(), List.of("45221000"));
        Result r = MatchingEngine.score(p, tender("PL", "45221111", "WORKS", null));
        assertThat(r.excluded()).isTrue();
        assertThat(r.score()).isZero();
        assertThat(MatchingEngine.score(p, tender("PL", "45233120", "WORKS", null)).excluded()).isFalse();
    }

    @Test
    void relatedCpvScoresByCommonPrefixAndAdditionalCodesCountLess() {
        Profile p = Profile.basic(List.of("45230000"), List.of(), null, List.of(), null, null, List.of());
        assertThat(MatchingEngine.score(p, tender("PL", "45210000", null, null)).components().getFirst().points())
                .isEqualTo(17);
        assertThat(MatchingEngine.score(p, tender("PL", "45000000", null, null)).components().getFirst().points())
                .isEqualTo(9);
        Result other = MatchingEngine.score(p, tender("PL", "72000000", null, null));
        assertThat(other.components().getFirst().points()).isZero();
        assertThat(codes(other.unmet())).contains("CPV_OTHER_SECTOR");

        Profile it = Profile.basic(List.of("72260000"), List.of(), null, List.of(), null, null, List.of());
        Result r = MatchingEngine.score(it, Tender.basic("PL", "48000000", List.of("48000000", "72263000"),
                "SERVICES", null));
        assertThat(r.components().getFirst().points()).isEqualTo(31);
        assertThat(r.met().getFirst().params()).containsEntry("tenderCpv", "72263000");
    }

    @Test
    void missingDataIsNeutralAndReportedNeverAMatch() {
        Profile empty = Profile.basic(List.of(), List.of(), null, List.of(), null, null, List.of());
        Result r = MatchingEngine.score(empty, tender("PL", null, null, null));
        assertThat(codes(r.missing())).containsExactly("PROFILE_NO_CPV", "PROFILE_NO_BASE", "TENDER_NO_LOCATION",
                "TENDER_VALUE_UNKNOWN", "TENDER_TYPE_UNKNOWN", "PROFILE_NO_REFERENCES");
        assertThat(r.score()).isEqualTo(0 + 15 + 7 + 5 + 5 + 0);
    }

    @Test
    void homeCountryWithoutDeclaredCountriesExcludesForeignTendersFromLocationPoints() {
        Profile p = Profile.basic(List.of("45000000"), List.of(), "PL", List.of(), null, null, List.of());
        assertThat(codes(MatchingEngine.score(p, tender("PL", "45000000", null, null)).met()))
                .contains("COUNTRY_OF_INTEREST");
        assertThat(codes(MatchingEngine.score(p, tender("DE", "45000000", null, null)).unmet()))
                .contains("COUNTRY_OUTSIDE");
    }

    @Test
    void valueBelowMinimumScoresLittle() {
        Result r = MatchingEngine.score(BUILDER, tender("PL", "45200000", "WORKS", "5000"));
        assertThat(r.components().get(2).points()).isEqualTo(4);
        assertThat(codes(r.unmet())).containsExactly("VALUE_BELOW_MIN");
    }

    @Test
    void helpers() {
        assertThat(MatchingEngine.significant("45210000")).isEqualTo("4521");
        assertThat(MatchingEngine.significant("45000000")).isEqualTo("45");
        assertThat(MatchingEngine.normalize("  Roboty DROGOWE –  Łódź ")).isEqualTo("roboty drogowe – lodz");
        assertThat(MatchingEngine.distanceKm(KRAKOW, GDANSK)).isBetween(470.0, 490.0);
        assertThat(MatchingEngine.containsWord("budowa mostu", "most")).isTrue();
        assertThat(MatchingEngine.containsWord("almost", "most")).isFalse();
    }

    @Test
    void scoreAlwaysWithinBounds() {
        String[] cpvs = {"45000000", "45214200", "72000000", "03000000", null};
        String[] countries = {"PL", "DE", "FR"};
        String[] types = {"WORKS", "SERVICES", "SUPPLIES", null};
        String[] values = {null, "1", "500000", "100000000"};
        GeoPoint[] places = {null, KRAKOW, GDANSK};
        for (Profile p : List.of(BUILDER, local(false), local(true))) {
            for (String c : cpvs) {
                for (String country : countries) {
                    for (String type : types) {
                        for (String v : values) {
                            for (GeoPoint place : places) {
                                int s = MatchingEngine.score(p, new Tender(country, c, List.of(), type,
                                        v == null ? null : new BigDecimal(v), place == null ? null : "PL213", place,
                                        "Budowa drogi")).score();
                                assertThat(s).isBetween(0, 100);
                            }
                        }
                    }
                }
            }
        }
    }
}
