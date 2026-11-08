package eu.tenderintel.ingestion.bzp;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * Reads the HTML body of a BZP notice. Every field is a numbered heading ({@code <h3>4.2.6.) Główny kod CPV: <span>…}),
 * its value is either inside the heading's {@code <span>} or the text that follows the heading. The HTML is untrusted
 * input: it is only parsed into text, never rendered.
 */
public final class BzpNoticeText {

    private static final Pattern STYLE = Pattern.compile("(?is)<(style|script)[^>]*>.*?</\\1>");
    private static final Pattern HEADING = Pattern.compile("(?is)<h([1-4])[^>]*>(.*?)</h\\1>");
    private static final Pattern SPAN_VALUE = Pattern.compile("(?is)<span[^>]*class=\"normal\"[^>]*>(.*?)</span>");
    private static final Pattern NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\)?\\s*");

    private BzpNoticeText() {
    }

    /** A numbered field: "4.2.6" / "Główny kod CPV" / "90910000-9 - Usługi sprzątania". */
    public record Field(String number, String label, String value) {
    }

    public static List<Field> fields(String html) {
        List<Field> out = new ArrayList<>();
        if (html == null) {
            return out;
        }
        String body = STYLE.matcher(html).replaceAll("");
        Matcher m = HEADING.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<String> inners = new ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            inners.add(m.group(2));
        }
        for (int i = 0; i < spans.size(); i++) {
            String inner = inners.get(i);
            Matcher span = SPAN_VALUE.matcher(inner);
            String value;
            String labelHtml;
            if (span.find()) {
                value = text(span.group(1));
                labelHtml = inner.substring(0, span.start());
            } else {
                int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
                value = text(body.substring(spans.get(i)[1], end));
                labelHtml = inner;
            }
            String label = text(labelHtml);
            Matcher num = NUMBER.matcher(label);
            String number = null;
            if (num.find()) {
                number = num.group(1);
                label = label.substring(num.end());
            }
            label = label.replaceAll("[:\\s]+$", "").strip();
            out.add(new Field(number, label, value));
        }
        return out;
    }

    /** Readable text of the whole notice (used as the citable notice document for AI analysis). */
    public static String plainText(String html) {
        if (html == null) {
            return "";
        }
        String s = STYLE.matcher(html).replaceAll("");
        s = s.replaceAll("(?is)<header[^>]*>.*?</header>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|h\\d|div|li|tr|table)>", "\n");
        s = HtmlUtils.htmlUnescape(s.replaceAll("<[^>]+>", " "));
        s = s.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    static String text(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", " ");
        s = HtmlUtils.htmlUnescape(s).replace(' ', ' ');
        return s.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n").strip();
    }
}
