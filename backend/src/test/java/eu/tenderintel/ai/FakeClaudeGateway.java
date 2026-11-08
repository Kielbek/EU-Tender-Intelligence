package eu.tenderintel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

/**
 * Stand-in for the Claude API in tests: records requests and answers with segments shaped like real citation
 * responses. The live API is never called from tests.
 */
@TestComponent
@Primary
public class FakeClaudeGateway implements ClaudeGateway {

    public record Call(String system, List<DocumentInput> documents, String instruction) {
    }

    public final List<Call> calls = new CopyOnWriteArrayList<>();
    public volatile boolean configured = true;
    public volatile Function<List<DocumentInput>, Response> responder = FakeClaudeGateway::defaultResponse;

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public Response analyze(String system, List<DocumentInput> documents, String instruction) {
        calls.add(new Call(system, documents, instruction));
        return responder.apply(documents);
    }

    @Override
    public String check() {
        return "claude-sonnet-5-5";
    }

    public void reset() {
        calls.clear();
        configured = true;
        responder = FakeClaudeGateway::defaultResponse;
    }

    /** Cites the notice (document 0) like the API does: claim text split into cited segments. */
    public static Response defaultResponse(List<DocumentInput> docs) {
        List<Segment> s = new ArrayList<>();
        s.add(new Segment("### SUMMARY\n- Budowa szkoły podstawowej w Testhausen.\n### SCOPE\n- ", List.of()));
        s.add(new Segment("Rozbudowa szkoły podstawowej",
                List.of(new Citation(0, "Neubau einer Grundschule", null, null, 7, 31))));
        s.add(new Segment("\n- Bez źródła – ten punkt powinien zostać odrzucony.\n### DEADLINES\n- Termin składania ofert: ",
                List.of()));
        s.add(new Segment("10.12.2026, 11:00 UTC",
                List.of(new Citation(0, "Time limit for receipt of tenders: 2026-12-10T11:00:00Z (UTC)", null, null,
                        300, 360))));
        if (docs.size() > 1) {
            s.add(new Segment("\n### SECURITY\n- Wadium: ", List.of()));
            s.add(new Segment("50 000 EUR", List.of(new Citation(1, "Wadium w wysokości 50 000 EUR.", 3, 4, null,
                    null))));
        }
        s.add(new Segment("\n### CONDITIONS\n- NOT_FOUND\n", List.of()));
        return new Response("claude-sonnet-5-5", "end_turn", s, new Usage(120_000, 3_000, 0, 0));
    }
}
