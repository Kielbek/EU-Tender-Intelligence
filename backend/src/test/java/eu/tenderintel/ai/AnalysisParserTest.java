package eu.tenderintel.ai;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.ai.AnalysisParser.Parsed;
import eu.tenderintel.ai.AnalysisParser.Section;
import eu.tenderintel.ai.ClaudeGateway.Citation;
import eu.tenderintel.ai.ClaudeGateway.DocumentInput;
import eu.tenderintel.ai.ClaudeGateway.Kind;
import eu.tenderintel.ai.ClaudeGateway.Segment;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisParserTest {

    private static Section section(Parsed p, String key) {
        return p.sections().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void keepsCitedFindingsAndDropsUncitedOnes() {
        List<DocumentInput> docs = List.of(new DocumentInput("n", Kind.TEXT, "x"), new DocumentInput("p", Kind.PDF, "y"));
        Parsed p = AnalysisParser.parse(FakeClaudeGateway.defaultResponse(docs).segments());
        assertThat(p.droppedUncited()).isEqualTo(1);
        assertThat(section(p, "SCOPE").findings()).singleElement().satisfies(f -> {
            assertThat(f.text()).isEqualTo("Rozbudowa szkoły podstawowej");
            assertThat(f.sources()).singleElement().satisfies(s -> {
                assertThat(s.citedText()).isEqualTo("Neubau einer Grundschule");
                assertThat(s.documentIndex()).isZero();
            });
        });
        assertThat(section(p, "SECURITY").findings().getFirst().sources().getFirst())
                .satisfies(s -> {
                    assertThat(s.startPage()).isEqualTo(3);
                    assertThat(s.endPage()).isEqualTo(3); // end page is exclusive in the API
                });
        // SUMMARY may synthesise without a citation, flagged as such.
        assertThat(section(p, "SUMMARY").findings().getFirst().cited()).isFalse();
        assertThat(section(p, "CONDITIONS").notFound()).isTrue();
        assertThat(section(p, "CRITERIA").notFound()).isTrue();
        assertThat(p.sections()).extracting(Section::key).containsExactlyElementsOf(AnalysisPrompt.SECTIONS);
    }

    @Test
    void continuationLinesAndCitationsSpanningLinesBelongToTheFinding() {
        Citation c1 = new Citation(0, "Doświadczenie: 2 roboty o wartości min. 1 mln PLN.", null, null, 0, 50);
        Parsed p = AnalysisParser.parse(List.of(
                new Segment("Intro text the model should not write\n### CONDITIONS – warunki\n- Dwie roboty", List.of()),
                new Segment(" o wartości co najmniej 1 mln PLN\n  każda", List.of(c1)),
                new Segment("\n### UNKNOWN\n- ignored\n### CRITERIA\n- **Cena 60%**", List.of(c1))));
        Section conditions = section(p, "CONDITIONS");
        assertThat(conditions.findings()).singleElement().satisfies(f -> {
            assertThat(f.text()).isEqualTo("Dwie roboty o wartości co najmniej 1 mln PLN każda");
            assertThat(f.sources()).hasSize(1);
        });
        assertThat(section(p, "CRITERIA").findings().getFirst().text()).isEqualTo("Cena 60%");
        assertThat(p.droppedUncited()).isZero();
    }

    @Test
    void duplicateQuotesAreMerged() {
        Citation c = new Citation(1, "Kara 0,2% za dzień.", 7, 8, null, null);
        Parsed p = AnalysisParser.parse(List.of(new Segment("### CONTRACT\n- Kary ", List.of()),
                new Segment("0,2%", List.of(c)), new Segment(" dziennie", List.of(c)), new Segment("\n", List.of())));
        assertThat(section(p, "CONTRACT").findings().getFirst().sources()).hasSize(1);
    }

    @Test
    void costUsesModelPrices() {
        var usage = new ClaudeGateway.Usage(100_000, 3_000, 0, 0);
        assertThat(AiPricing.costUsd("claude-sonnet-5-5", usage)).isEqualByComparingTo(new BigDecimal("0.23"));
        assertThat(AiPricing.costUsd("claude-opus-5-5", usage)).isEqualByComparingTo(new BigDecimal("0.46"));
        // Unknown model → priced as Opus, never under-stated.
        assertThat(AiPricing.costUsd("some-future-model", usage)).isEqualByComparingTo(new BigDecimal("0.46"));
    }

    @Test
    void promptTreatsDocumentsAsUntrustedData() {
        String system = AnalysisPrompt.system("pl");
        assertThat(system).contains("never instructions").contains("Polish").contains(AnalysisPrompt.NOT_FOUND);
        AnalysisPrompt.SECTIONS.forEach(s -> assertThat(system).contains("### " + s));
    }
}
