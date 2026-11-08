package eu.tenderintel.ingestion.ted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.tenderintel.ingestion.ChangeKind;
import eu.tenderintel.ingestion.NormalizedNotice;
import eu.tenderintel.ingestion.NormalizedNotice.FormType;
import eu.tenderintel.ingestion.TenderStatusRules;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parser rules checked against real TED notices (see src/test/resources/ted/README.md). */
class TedNoticeParserTest {

    static JsonNode sample;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = TedNoticeParserTest.class.getResourceAsStream("/ted/sample-2026-11-06.json")) {
            sample = JsonMapper.builder().build().readTree(in);
        }
    }

    static NormalizedNotice parse(String key) {
        return TedNoticeParser.parse(sample.get(key));
    }

    @Test
    void singleLotCompetition() {
        NormalizedNotice n = parse("cn_pl_single");
        assertThat(n.externalId()).isEqualTo("771400-2026");
        assertThat(n.orderKey()).isEqualTo(202_600_771_400L);
        assertThat(n.procedureKey()).isEqualTo("eforms:3cd92e09-232d-4bfb-bd55-08c952779087");
        assertThat(n.formType()).isEqualTo(FormType.COMPETITION);
        assertThat(n.changeKind()).isEqualTo(ChangeKind.COMPETITION);
        assertThat(n.title()).startsWith("Druk i dystrybucja publikacji");
        assertThat(n.titleLang()).isEqualTo("pl");
        assertThat(n.buyer().name()).isEqualTo("Centralna Komisja Egzaminacyjna");
        assertThat(n.buyer().nationalId()).isEqualTo("527 22 16 679");
        assertThat(n.countryCode()).isEqualTo("PL");
        assertThat(n.nutsCode()).isEqualTo("PL911");
        assertThat(n.contractType()).isEqualTo("SUPPLIES");
        assertThat(n.procedureType()).isEqualTo("open");
        assertThat(n.mainCpv()).isEqualTo("30199700");
        assertThat(n.estimatedValue()).isEqualByComparingTo("367994.72");
        assertThat(n.currency()).isEqualTo("PLN");
        assertThat(n.deadlines()).containsExactly(Instant.parse("2026-12-08T08:00:00Z"));
        assertThat(n.deadlineTz()).isEqualTo("+01:00");
        assertThat(n.referenceNumber()).isEqualTo("CKE/7/2026");
        assertThat(n.publicationDate()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(n.publishedAt()).isEqualTo(Instant.parse("2026-11-05T23:00:00Z"));
        assertThat(n.externalUrl()).isEqualTo("https://ted.europa.eu/en/notice/-/detail/771400-2026");
        assertThat(n.lots()).isEmpty();
    }

    @Test
    void multiLotKeepsPerLotDeadlinesAndValues() {
        NormalizedNotice n = parse("cn_multilot");
        assertThat(n.lots()).hasSize(29);
        assertThat(n.lots().getFirst().estimatedValue()).isEqualByComparingTo("147933.88");
        assertThat(n.lots().getFirst().currency()).isEqualTo("RON");
        assertThat(n.lots().getFirst().deadline()).isEqualTo(Instant.parse("2026-11-13T13:00:00Z"));
        // One lot already closed on the publication day: the effective deadline is the next open one.
        Instant now = Instant.parse("2026-11-07T12:00:00Z");
        assertThat(TenderStatusRules.effectiveDeadline(n.deadlines(), now)).isEqualTo(Instant.parse("2026-11-13T13:00:00Z"));
        assertThat(TenderStatusRules.effectiveDeadline(n.deadlines(), Instant.parse("2026-12-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2026-11-13T13:00:00Z"));
    }

    @Test
    void changeAndCancellationAreRecognised() {
        NormalizedNotice change = parse("cn_change");
        assertThat(change.changeKind()).isEqualTo(ChangeKind.CHANGE);
        assertThat(change.changeReason()).isEqualTo("info-release");

        NormalizedNotice cancel = parse("cn_cancel");
        assertThat(cancel.changeKind()).isEqualTo(ChangeKind.CANCELLATION);
        assertThat(TenderStatusRules.derive(cancel, null, "OPEN", Instant.now())).isEqualTo("CANCELLED");
        // Bilingual notice (Swedish first): title, description and buyer follow the same language.
        assertThat(cancel.titleLang()).isEqualTo("sv");
        assertThat(cancel.buyer().name()).isEqualTo("Korsholms kommun");
    }

    @Test
    void awardWithAlignedWinnerLists() {
        NormalizedNotice n = parse("can_aligned");
        assertThat(n.formType()).isEqualTo(FormType.RESULT);
        assertThat(n.changeKind()).isEqualTo(ChangeKind.AWARD);
        assertThat(n.winners()).singleElement().satisfies(w -> {
            assertThat(w.name()).isEqualTo("WSP SPAIN-APIA S.A.U.");
            assertThat(w.countryCode()).isEqualTo("ES");
            assertThat(w.value()).isEqualByComparingTo("201070.03");
            assertThat(w.currency()).isEqualTo("EUR");
        });
        assertThat(n.tendersReceived()).isEqualTo(3);
        assertThat(n.awardDate()).isEqualTo(LocalDate.of(2025, 11, 27));
        assertThat(TenderStatusRules.derive(n, null, null, Instant.now())).isEqualTo("AWARDED");
    }

    @Test
    void misalignedWinnerListsNeverGetGuessedValues() {
        NormalizedNotice n = parse("can_mismatch");
        assertThat(n.winners()).isNotEmpty().allSatisfy(w -> assertThat(w.value()).isNull());
    }

    @Test
    void resultWithoutWinnerIsClosedNotAwarded() {
        NormalizedNotice n = parse("can_no_winner");
        assertThat(n.winners()).isEmpty();
        assertThat(TenderStatusRules.derive(n, null, "OPEN", Instant.now())).isEqualTo("CLOSED");
    }

    @Test
    void otherFormTypes() {
        assertThat(parse("can_modif").changeKind()).isEqualTo(ChangeKind.MODIFICATION);
        assertThat(parse("veat").formType()).isEqualTo(FormType.DIRECT_AWARD);
        NormalizedNotice pin = parse("pin");
        assertThat(pin.changeKind()).isEqualTo(ChangeKind.PRIOR_INFORMATION);
        assertThat(TenderStatusRules.derive(pin, null, null, Instant.now())).isEqualTo("PLANNED");
        assertThat(parse("no_procedure_id").procedureKey()).startsWith("ted:");
    }

    @Test
    void nonEuBuyersUtcDeadlinesAndUnusualCurrencies() {
        NormalizedNotice norway = parse("norway");
        assertThat(norway.countryCode()).isEqualTo("NO");
        assertThat(norway.deadlines()).containsExactly(Instant.parse("2027-01-04T11:00:00Z"));
        assertThat(norway.deadlineTz()).isEqualTo("Z");
        assertThat(norway.titleLang()).isEqualTo("en");

        NormalizedNotice cop = parse("cop_currency");
        assertThat(cop.currency()).isEqualTo("COP");
        assertThat(cop.estimatedValue()).isEqualByComparingTo(new BigDecimal("1680000000"));
        assertThat(cop.countryCode()).isEqualTo("CO");
        assertThat(cop.deadlines()).containsExactly(Instant.parse("2026-12-14T14:00:59Z"));
    }

    @Test
    void deadlineWithoutTimeUsesStartOfDayNeverLater() {
        OffsetDateTime d = TedNoticeParser.deadline("2026-11-13+02:00", null);
        assertThat(d.toInstant()).isEqualTo(Instant.parse("2026-11-12T22:00:00Z"));
    }

    @Test
    void rejectsMalformedPublicationNumbers() {
        assertThatThrownBy(() -> TedNoticeParser.orderKey("ABC")).isInstanceOf(IllegalArgumentException.class);
        assertThat(TedNoticeParser.orderKey("1-2027")).isGreaterThan(TedNoticeParser.orderKey("99999999-2026"));
    }

    @Test
    void cleansControlCharacters() {
        assertThat(TedNoticeParser.clean("  a\u0000b c\n ", 100)).isEqualTo("ab c");
        assertThat(TedNoticeParser.clean("   ", 100)).isNull();
    }
}
