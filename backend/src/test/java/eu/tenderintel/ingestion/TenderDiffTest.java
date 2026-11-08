package eu.tenderintel.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenderDiffTest {

    static TenderUpsertService.State state(List<String> cpv, BigDecimal value, Instant deadline, String description) {
        return new TenderUpsertService.State("Title", "pl", description, "PL", null, null, null, null, "WORKS",
                "open", "45000000", cpv, value, value == null ? null : "PLN", null, deadline, null, "OPEN", "pl", null,
                null);
    }

    @Test
    void cpvOrderAndNumericScaleAreNotChanges() {
        var a = state(List.of("45000000", "45200000"), new BigDecimal("100.00"), null, "x");
        var b = state(List.of("45200000", "45000000"), new BigDecimal("100"), null, "x");
        assertThat(TenderUpsertService.diff(a, b)).isEmpty();
    }

    @Test
    void descriptionChangeIsReportedWithoutCopyingText() {
        var a = state(List.of(), null, null, "old secret text");
        var b = state(List.of(), null, null, "new text");
        List<Map<String, Object>> diff = TenderUpsertService.diff(a, b);
        assertThat(diff).singleElement().satisfies(d -> {
            assertThat(d).containsEntry("field", "description");
            assertThat(d).doesNotContainKeys("old", "new");
        });
    }

    @Test
    void mergeKeepsValuesTheNewNoticeDoesNotCarry() {
        Instant deadline = Instant.parse("2026-12-01T10:00:00Z");
        var before = state(List.of("45000000"), new BigDecimal("5"), deadline, "d");
        var award = new TenderUpsertService.State("Title", "pl", null, "PL", null, null, null, null, null, null,
                null, List.of(), null, null, null, null, null, "AWARDED", "pl", null, null);
        var merged = before.mergedWith(award);
        assertThat(merged.submissionDeadline()).isEqualTo(deadline);
        assertThat(merged.estimatedValue()).isEqualByComparingTo("5");
        assertThat(merged.cpvCodes()).containsExactly("45000000");
        assertThat(merged.status()).isEqualTo("AWARDED");
        assertThat(TenderUpsertService.diff(before, merged)).extracting(d -> d.get("field")).containsExactly("status");
    }
}
