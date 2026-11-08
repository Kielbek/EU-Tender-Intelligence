package eu.tenderintel.ingestion.bzp;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.ingestion.ChangeKind;
import eu.tenderintel.ingestion.NormalizedNotice;
import eu.tenderintel.ingestion.NormalizedNotice.FormType;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real BZP notices of 2026-11-05 (contact details anonymised). */
class BzpNoticeParserTest {

    static JsonNode samples;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = BzpNoticeParserTest.class.getResourceAsStream("/bzp/notices.json")) {
            samples = JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void contractNoticeWithParts() {
        NormalizedNotice n = BzpNoticeParser.parse(samples.get("contractMultiPart"));
        assertThat(n.externalId()).isEqualTo("2026/BZP 00523249/01");
        assertThat(n.procedureKey()).isEqualTo("bzp:ocds-148610-e67f2b11-82a5-4666-a2cd-2835579b51b1");
        assertThat(n.formType()).isEqualTo(FormType.COMPETITION);
        assertThat(n.changeKind()).isEqualTo(ChangeKind.COMPETITION);
        assertThat(n.title()).startsWith("Kompleksowe utrzymanie czystości wewnątrz Hali Widowiskowo-Sportowej");
        assertThat(n.titleLang()).isEqualTo("pl");
        assertThat(n.countryCode()).isEqualTo("PL");
        assertThat(n.nutsCode()).isEqualTo("PL618");
        assertThat(n.city()).isEqualTo("Świecie");
        assertThat(n.contractType()).isEqualTo("SERVICES");
        assertThat(n.procedureType()).isEqualTo("pl-basic");
        assertThat(n.mainCpv()).isEqualTo("90910000");
        assertThat(n.deadlines()).containsExactly(Instant.parse("2026-11-17T08:00:00Z"));
        assertThat(n.deadlineTz()).isEqualTo("+01:00");
        assertThat(n.documentsUrl()).isEqualTo(
                "https://ezamowienia.gov.pl/mp-client/search/list/ocds-148610-e67f2b11-82a5-4666-a2cd-2835579b51b1");
        assertThat(n.externalUrl()).startsWith(BzpNoticeParser.NOTICE_URL);
        assertThat(n.referenceNumber()).isEqualTo("1,3,1");
        assertThat(n.lots()).hasSize(3);
        assertThat(n.description()).startsWith("Część 1: Świadczenie usług utrzymania porządku");
        assertThat(n.buyer().name()).isEqualTo("Vistula-Park Świecie Sp. z o.o.");
        assertThat(n.buyer().nationalId()).isEqualTo("5591717760");
        assertThat(n.estimatedValue()).isNull(); // BZP contract notices do not publish the estimated value
    }

    @Test
    void singleContractNotice() {
        NormalizedNotice n = BzpNoticeParser.parse(samples.get("contractSingle"));
        assertThat(n.lots()).isEmpty();
        assertThat(n.description()).doesNotStartWith("Część");
        assertThat(n.mainCpv()).matches("\\d{8}");
        assertThat(n.cpvCodes()).contains(n.mainCpv());
    }

    @Test
    void resultNoticeWithWinnersAndPrices() {
        NormalizedNotice n = BzpNoticeParser.parse(samples.get("resultAward"));
        assertThat(n.formType()).isEqualTo(FormType.RESULT);
        assertThat(n.changeKind()).isEqualTo(ChangeKind.AWARD);
        assertThat(n.contractType()).isEqualTo("SUPPLIES");
        assertThat(n.winners()).hasSize(5);
        assertThat(n.winners().getFirst().name()).isEqualTo("Asclepios S.A.");
        assertThat(n.winners().getFirst().value()).isEqualByComparingTo("309.10");
        assertThat(n.winners().getFirst().currency()).isEqualTo("PLN");
        // Sum of the estimated values of the five parts.
        assertThat(n.estimatedValue()).isEqualByComparingTo(new BigDecimal("444.33").add(new BigDecimal("550088.89"))
                .add(new BigDecimal("66780.56")).add(new BigDecimal("80833.33")).add(new BigDecimal("198148.15")));
        assertThat(n.currency()).isEqualTo("PLN");
        assertThat(n.tendersReceived()).isNull(); // several parts: no single number
    }

    @Test
    void cancelledProcedure() {
        NormalizedNotice n = BzpNoticeParser.parse(samples.get("resultCancelled"));
        assertThat(n.changeKind()).isEqualTo(ChangeKind.CANCELLATION);
        assertThat(n.winners()).isEmpty(); // the API sends an all-null contractor
    }

    @Test
    void changeNoticeWithNewDeadline() {
        NormalizedNotice n = BzpNoticeParser.parse(samples.get("updateDeadline"));
        assertThat(n.changeKind()).isEqualTo(ChangeKind.CHANGE);
        assertThat(n.formType()).isEqualTo(FormType.COMPETITION);
        // "Po zmianie: 2026-11-10 09:30" Warsaw time.
        assertThat(n.deadlines()).containsExactly(Instant.parse("2026-11-10T08:30:00Z"));
        assertThat(n.procedureKey()).startsWith("bzp:ocds-");
    }

    @Test
    void helpers() {
        assertThat(BzpNoticeParser.orderKey("2026/BZP 00523249/01")).isEqualTo(2026_0052324901L);
        assertThat(BzpNoticeParser.orderKey("2026/BZP 00523249/02"))
                .isGreaterThan(BzpNoticeParser.orderKey("2026/BZP 00523249/01"));
        assertThat(BzpNoticeParser.cancelled("uniewaznienie;uniewaznienie")).isTrue();
        assertThat(BzpNoticeParser.cancelled("uniewaznienie;zawarcieUmowy")).isFalse();
        assertThat(BzpNoticeParser.cancelled(null)).isFalse();
        assertThat(BzpNoticeParser.procedureType("Zamówienie udzielane jest w trybie przetargu nieograniczonego"))
                .isEqualTo("open");
        assertThat(BzpNoticeParser.contractType("Works", null)).isEqualTo("WORKS");
        assertThat(BzpNoticeParser.amounts(java.util.List.of("1 234 567,89 PLN")).getFirst()[0])
                .isEqualByComparingTo("1234567.89");
    }

    @Test
    void plainTextKeepsTheNoticeReadable() {
        String text = BzpNoticeText.plainText(BzpNoticeParser.text(samples.get("contractMultiPart"), "htmlBody"));
        assertThat(text).contains("SEKCJA V - KWALIFIKACJA WYKONAWCÓW").contains("8.1.) Termin składania ofert:")
                .doesNotContain("<").doesNotContain("font-family");
    }
}
