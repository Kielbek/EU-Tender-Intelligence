package eu.tenderintel.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import eu.tenderintel.ingestion.ted.TedNoticeParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Robustness check over a full publication day downloaded from TED (not committed – ~10 MB). Run with
 * TED_FULL_DAY_FILE=/path/to/day.json ./mvnw test -Dtest=TedFullDayParseCheck
 */
@EnabledIfEnvironmentVariable(named = "TED_FULL_DAY_FILE", matches = ".+")
class TedFullDayParseCheck {

    @Test
    void parsesEveryNoticeOfARealDay() throws Exception {
        JsonNode all = JsonMapper.builder().build().readTree(Files.readString(Path.of(System.getenv("TED_FULL_DAY_FILE"))));
        List<String> failures = new ArrayList<>();
        Map<String, Integer> kinds = new TreeMap<>();
        Map<String, Integer> stats = new TreeMap<>();
        for (JsonNode n : all) {
            try {
                NormalizedNotice p = TedNoticeParser.parse(n);
                kinds.merge(p.formType() + "/" + p.changeKind(), 1, Integer::sum);
                if (p.estimatedValue() != null) stats.merge("withValue", 1, Integer::sum);
                if (!p.deadlines().isEmpty()) stats.merge("withDeadline", 1, Integer::sum);
                if (p.nutsCode() != null) stats.merge("withNuts", 1, Integer::sum);
                if (!p.winners().isEmpty()) stats.merge("withWinners", 1, Integer::sum);
                if (p.winners().stream().anyMatch(w -> w.value() != null)) stats.merge("winnerWithValue", 1, Integer::sum);
                if (!p.lots().isEmpty()) stats.merge("multiLot", 1, Integer::sum);
                if (p.titleLang() == null) stats.merge("noTitleLang", 1, Integer::sum);
                if (p.buyer().name() == null) stats.merge("noBuyerName", 1, Integer::sum);
                if (p.procedureKey().startsWith("ted:")) stats.merge("fallbackKey", 1, Integer::sum);
                if (p.tendersReceived() != null) stats.merge("tendersReceived", 1, Integer::sum);
            } catch (RuntimeException e) {
                failures.add(n.path("publication-number").asString() + " " + e);
            }
        }
        System.out.println("kinds=" + kinds);
        System.out.println("stats=" + stats + " total=" + all.size());
        failures.stream().limit(20).forEach(f -> System.out.println("FAIL " + f));
        assertThat(failures).isEmpty();
    }
}
