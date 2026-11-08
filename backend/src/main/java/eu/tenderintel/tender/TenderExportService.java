package eu.tenderintel.tender;

import eu.tenderintel.tender.TenderDtos.TenderSummary;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.stereotype.Service;

/**
 * CSV (RFC 4180, UTF-8 with BOM for Excel) and XLSX exports of search results. Cells that a spreadsheet could
 * interpret as a formula are neutralised (CSV/formula injection); XLSX cells are always written as text or numbers.
 */
@Service
public class TenderExportService {

    public static final int MAX_ROWS = 5_000;

    record Column(String pl, String en, Function<TenderSummary, Object> value) {
    }

    static final List<Column> COLUMNS = List.of(
            new Column("Tytuł", "Title", TenderSummary::title),
            new Column("Kraj", "Country", TenderSummary::countryCode),
            new Column("Miejscowość", "City", TenderSummary::city),
            new Column("Zamawiający", "Contracting authority", TenderSummary::authorityName),
            new Column("CPV", "CPV", TenderSummary::mainCpv),
            new Column("Opis CPV", "CPV description", TenderSummary::mainCpvLabel),
            new Column("Rodzaj zamówienia", "Contract type", TenderSummary::contractType),
            new Column("Tryb", "Procedure", TenderSummary::procedureType),
            new Column("Wartość szacunkowa", "Estimated value", TenderSummary::estimatedValue),
            new Column("Waluta", "Currency", TenderSummary::currency),
            new Column("Wartość (EUR)", "Value (EUR)", TenderSummary::estimatedValueEur),
            new Column("Data publikacji (UTC)", "Published (UTC)", TenderSummary::publishedAt),
            new Column("Termin składania ofert (UTC)", "Submission deadline (UTC)", TenderSummary::submissionDeadline),
            new Column("Status", "Status", TenderSummary::status),
            new Column("Ogłoszenie (TED)", "Notice (TED)", TenderSummary::originalUrl),
            new Column("ID", "ID", t -> t.id().toString()));

    public void writeCsv(List<TenderSummary> rows, String lang, OutputStream out) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write('﻿');
        w.write(String.join(",", COLUMNS.stream().map(c -> csv(header(c, lang))).toList()));
        w.write("\r\n");
        for (TenderSummary t : rows) {
            w.write(String.join(",", COLUMNS.stream().map(c -> csv(text(c.value().apply(t)))).toList()));
            w.write("\r\n");
        }
        w.flush();
    }

    public void writeXlsx(List<TenderSummary> rows, String lang, OutputStream out) throws IOException {
        Workbook wb = new Workbook(out, "EU Tender Intelligence", "1.0");
        Worksheet ws = wb.newWorksheet("pl".equals(lang) ? "Przetargi" : "Tenders");
        for (int c = 0; c < COLUMNS.size(); c++) {
            ws.value(0, c, header(COLUMNS.get(c), lang));
            ws.style(0, c).bold().fillColor("DCE6F2").set();
        }
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < COLUMNS.size(); c++) {
                Object v = COLUMNS.get(c).value().apply(rows.get(r));
                if (v instanceof BigDecimal n) {
                    ws.value(r + 1, c, n);
                    ws.style(r + 1, c).format("#,##0.00").set();
                } else if (v != null) {
                    ws.value(r + 1, c, text(v));
                }
            }
        }
        ws.freezePane(0, 1);
        ws.width(0, 60);
        ws.width(3, 40);
        ws.width(5, 40);
        wb.finish();
    }

    private static String header(Column c, String lang) {
        return "pl".equals(lang) ? c.pl() : c.en();
    }

    private static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Instant i) {
            return i.atOffset(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ');
        }
        if (v instanceof BigDecimal b) {
            return b.toPlainString();
        }
        return v.toString();
    }

    /** RFC 4180 quoting plus neutralisation of spreadsheet formulas (=, +, -, @, tab, CR). */
    static String csv(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !v.matches("-?\\d+(\\.\\d+)?")) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") || v.startsWith("'")) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
