package eu.tenderintel.ai;

import eu.tenderintel.ai.ClaudeGateway.Citation;
import eu.tenderintel.ai.ClaudeGateway.Segment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the model's cited text segments into sections of findings. A citation belongs to every line its segment
 * overlaps. Findings without a citation are dropped (counted in {@code droppedUncited}); only SUMMARY bullets may stand
 * without one and are marked {@code cited = false}. Every quote shown to users is the API's {@code cited_text}.
 */
public final class AnalysisParser {

    private AnalysisParser() {
    }

    public record SourceRef(int documentIndex, String citedText, Integer startPage, Integer endPage) {
    }

    public record Finding(String text, boolean cited, List<SourceRef> sources) {
    }

    public record Section(String key, boolean notFound, List<Finding> findings) {
    }

    public record Parsed(List<Section> sections, int droppedUncited) {
    }

    public static Parsed parse(List<Segment> segments) {
        StringBuilder all = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        for (Segment s : segments) {
            int start = all.length();
            all.append(s.text());
            ranges.add(new int[] {start, all.length()});
        }
        Map<String, List<Finding>> found = new LinkedHashMap<>();
        Set<String> notFound = new LinkedHashSet<>();
        String section = null;
        StringBuilder item = null;
        Set<Citation> itemCites = new LinkedHashSet<>();
        int dropped = 0;
        int pos = 0;
        String text = all.toString();
        String[] lines = text.split("\n", -1);
        for (String raw : lines) {
            int lineStart = pos;
            int lineEnd = pos + raw.length();
            pos = lineEnd + 1;
            String line = raw.strip();
            Set<Citation> lineCites = new LinkedHashSet<>();
            for (int i = 0; i < segments.size(); i++) {
                int[] r = ranges.get(i);
                boolean overlaps = r[0] < Math.max(lineEnd, lineStart + 1) && r[1] > lineStart;
                if (overlaps) {
                    lineCites.addAll(segments.get(i).citations());
                }
            }
            if (line.startsWith("###")) {
                dropped += flush(found, section, item, itemCites);
                item = null;
                itemCites = new LinkedHashSet<>();
                String key = line.replace("#", "").strip().split("[\\s–:-]", 2)[0].toUpperCase();
                section = AnalysisPrompt.SECTIONS.contains(key) ? key : null;
                continue;
            }
            if (section == null || line.isEmpty()) {
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) {
                dropped += flush(found, section, item, itemCites);
                String body = line.substring(2).strip();
                if (body.equals(AnalysisPrompt.NOT_FOUND)) {
                    notFound.add(section);
                    item = null;
                    itemCites = new LinkedHashSet<>();
                    continue;
                }
                item = new StringBuilder(body);
                itemCites = new LinkedHashSet<>(lineCites);
            } else if (item != null) {
                item.append(' ').append(line);
                itemCites.addAll(lineCites);
            }
        }
        dropped += flush(found, section, item, itemCites);
        List<Section> sections = new ArrayList<>();
        for (String key : AnalysisPrompt.SECTIONS) {
            List<Finding> list = found.getOrDefault(key, List.of());
            sections.add(new Section(key, list.isEmpty(), list));
        }
        return new Parsed(sections, dropped);
    }

    /** Adds the pending finding; returns 1 if it was dropped for lack of citations. */
    private static int flush(Map<String, List<Finding>> found, String section, StringBuilder item,
            Set<Citation> cites) {
        if (section == null || item == null) {
            return 0;
        }
        String text = item.toString().replace("**", "").strip();
        if (text.isEmpty()) {
            return 0;
        }
        if (cites.isEmpty() && !"SUMMARY".equals(section)) {
            return 1;
        }
        List<SourceRef> refs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Citation c : cites) {
            String quote = c.citedText() == null ? "" : c.citedText().strip();
            if (quote.isEmpty() || !seen.add(c.documentIndex() + "|" + quote)) {
                continue;
            }
            Integer endPage = c.endPageExclusive() == null ? null : Math.max(c.startPage(), c.endPageExclusive() - 1);
            refs.add(new SourceRef(c.documentIndex(), quote, c.startPage(), endPage));
        }
        if (refs.isEmpty() && !"SUMMARY".equals(section)) {
            return 1;
        }
        found.computeIfAbsent(section, k -> new ArrayList<>()).add(new Finding(text, !refs.isEmpty(), refs));
        return 0;
    }
}
