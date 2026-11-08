package eu.tenderintel.ingestion.bzp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BzpResumeTest {

    @Test
    void aRetryContinuesWhereTheFailedJobStopped() {
        Map<String, Instant> r = BzpIngestionJobs.resumePoints("TenderResultNotice@2026-10-20T08:00:00Z",
                LocalDate.parse("2026-11-04"));
        Instant end = Instant.parse("2026-11-04T23:00:00Z"); // midnight 5 Nov in Warsaw (CET)
        // Types imported before the failure are finished; the interrupted one resumes at its cursor.
        for (String t : BzpNoticeParser.TYPES) {
            if (t.equals("TenderResultNotice")) {
                assertThat(r.get(t)).isEqualTo(Instant.parse("2026-10-20T08:00:00Z"));
                break;
            }
            assertThat(r.get(t)).as(t).isEqualTo(end);
        }
        assertThat(BzpIngestionJobs.resumePoints(null, LocalDate.parse("2026-11-04"))).isEmpty();
        assertThat(BzpIngestionJobs.resumePoints("garbage", LocalDate.parse("2026-11-04"))).isEmpty();
        assertThat(BzpIngestionJobs.resumePoints("Unknown@2026-10-20T08:00:00Z", LocalDate.parse("2026-11-04")))
                .isEmpty();
    }
}
