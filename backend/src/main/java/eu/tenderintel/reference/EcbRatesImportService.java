package eu.tenderintel.reference;

import eu.tenderintel.config.AppProperties;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Imports ECB euro foreign exchange reference rates (eurofxref). Only currencies present in {@code currencies} are
 * stored. Idempotent.
 */
@Service
public class EcbRatesImportService {

    private final RestClient http;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final TransactionTemplate tx;

    EcbRatesImportService(RestClient.Builder builder, JdbcTemplate jdbc, AppProperties props,
            TransactionTemplate tx) {
        this.tx = tx;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = builder.requestFactory(factory).defaultHeader("User-Agent", "EU-Tender-Intelligence/1.0").build();
        this.jdbc = jdbc;
        this.props = props;
    }

    public record Rate(String currency, LocalDate date, BigDecimal ratePerEur) {
    }

    public record Result(int fetched, int stored) {
    }

    public Result importDaily() {
        return importFrom(props.reference().ecbDailyUrl());
    }

    public Result importLast90Days() {
        return importFrom(props.reference().ecbHistory90dUrl());
    }

    private Result importFrom(String url) {
        byte[] xml = http.get().uri(url).retrieve().body(byte[].class);
        List<Rate> rates = parse(xml);
        return new Result(rates.size(), store(rates));
    }

    int store(List<Rate> rates) {
        Set<String> known = Set.copyOf(jdbc.queryForList("select code from currencies", String.class));
        List<Object[]> rows = rates.stream().filter(r -> known.contains(r.currency()))
                .map(r -> new Object[] {r.currency(), r.date(), r.ratePerEur()}).toList();
        tx.executeWithoutResult(status -> jdbc.batchUpdate("""
                insert into exchange_rates (currency, rate_date, rate_per_eur, source) values (?, ?, ?, 'ECB')
                on conflict (currency, rate_date) do update set rate_per_eur = excluded.rate_per_eur
                """, rows));
        return rows.size();
    }

    /** Parses the eurofxref XML with external entities and DTDs disabled (XXE protection). */
    static List<Rate> parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(true);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            NodeList cubes = doc.getElementsByTagNameNS("*", "Cube");
            List<Rate> rates = new ArrayList<>();
            for (int i = 0; i < cubes.getLength(); i++) {
                Element day = (Element) cubes.item(i);
                if (!day.hasAttribute("time")) {
                    continue;
                }
                LocalDate date = LocalDate.parse(day.getAttribute("time"));
                NodeList children = day.getElementsByTagNameNS("*", "Cube");
                for (int j = 0; j < children.getLength(); j++) {
                    Element c = (Element) children.item(j);
                    if (c.hasAttribute("currency") && c.hasAttribute("rate")) {
                        rates.add(new Rate(c.getAttribute("currency"), date, new BigDecimal(c.getAttribute("rate"))));
                    }
                }
            }
            if (rates.isEmpty()) {
                throw new IllegalStateException("ECB response contained no rates");
            }
            return rates;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse ECB rates", e);
        }
    }
}
