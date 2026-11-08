package eu.tenderintel.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.tenderintel.IntegrationTest;
import eu.tenderintel.TestApi;
import eu.tenderintel.TestApi.Session;
import eu.tenderintel.tender.TenderFixtures;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@IntegrationTest
class AnalysisIntegrationTest {

    static final String T1 = TenderFixtures.T1;

    @Autowired
    MockMvc mvc;
    @Autowired
    TestApi api;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    FakeClaudeGateway claude;

    @BeforeEach
    void fixtures() {
        TenderFixtures.insert(jdbc);
        claude.reset();
    }

    private String org(Session s) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations").with(s.bearer())).andReturn().getResponse())
                .get(0).get("id").asString();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(15));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition within 15 s").isBefore(end);
            Thread.sleep(50);
        }
    }

    private JsonNode latest(Session s, String org) throws Exception {
        return api.readJson(mvc.perform(get("/api/v1/organizations/" + org + "/tenders/" + T1
                + "/analyses/latest?lang=pl").with(s.bearer())).andReturn().getResponse());
    }

    private JsonNode awaitDone(Session s, String org) throws Exception {
        JsonNode[] last = new JsonNode[1];
        await(() -> {
            try {
                last[0] = latest(s, org);
                String st = last[0].path("status").asString();
                return st.equals("DONE") || st.equals("FAILED");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return last[0];
    }

    static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (text != null) {
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] zip(String n1, byte[] c1, String n2, byte[] c2) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("SWZ/" + n1));
            z.write(c1);
            z.putNextEntry(new ZipEntry("__MACOSX/._" + n1));
            z.write(new byte[] {0, 1});
            z.putNextEntry(new ZipEntry(n2));
            z.write(c2);
        }
        return out.toByteArray();
    }

    @Test
    void publicAnalysisIsSharedOrganizationUploadsArePrivateAndLimitsApply() throws Exception {
        Session alice = api.registerVerifyLogin("alice-" + System.nanoTime() + "@ai.test");
        Session bob = api.registerVerifyLogin("bob-" + System.nanoTime() + "@ai.test");
        String aliceOrg = org(alice);
        String bobOrg = org(bob);
        String aliceBase = "/api/v1/organizations/" + aliceOrg + "/tenders/" + T1;
        String bobBase = "/api/v1/organizations/" + bobOrg + "/tenders/" + T1;

        mvc.perform(get(aliceBase + "/analyses/latest?lang=pl").with(alice.bearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("analysis_missing"));
        mvc.perform(get("/api/v1/organizations/" + aliceOrg + "/ai/quota").with(alice.bearer()))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.planCode").value("FREE"))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.remaining").value(2));

        // 1. Public analysis of the notice data.
        mvc.perform(post(aliceBase + "/analyses?lang=pl").with(alice.bearer())).andExpect(status().isAccepted());
        JsonNode done = awaitDone(alice, aliceOrg);
        assertThat(done.get("status").asString()).isEqualTo("DONE");
        assertThat(done.get("scope").asString()).isEqualTo("PUBLIC");
        assertThat(claude.calls).hasSize(1);
        assertThat(claude.calls.getFirst().documents()).singleElement().satisfies(d -> {
            assertThat(d.title()).isEqualTo("Ogłoszenie o zamówieniu (oficjalna treść)");
            assertThat(d.data()).contains("Neubau einer Grundschule").contains("2026-12-10T11:00:00Z");
        });
        assertThat(claude.calls.getFirst().system()).contains("Polish").contains("never instructions");
        JsonNode result = done.get("result");
        assertThat(result.get("droppedUncited").asInt()).isEqualTo(1);
        JsonNode scope = result.get("sections").get(1);
        assertThat(scope.get("key").asString()).isEqualTo("SCOPE");
        assertThat(scope.get("findings").get(0).get("sources").get(0).get("quote").asString())
                .isEqualTo("Neubau einer Grundschule");
        assertThat(scope.get("findings").get(0).get("sources").get(0).get("documentTitle").asString())
                .isEqualTo("Ogłoszenie o zamówieniu (oficjalna treść)");

        // 2. Another organization re-opens the same public analysis: no model call, no quota used.
        mvc.perform(post(bobBase + "/analyses?lang=pl").with(bob.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(claude.calls).hasSize(1);
        mvc.perform(get("/api/v1/organizations/" + bobOrg + "/ai/quota").with(bob.bearer()))
                .andExpect(jsonPath("$.used").value(0));
        // Another language is a separate analysis.
        mvc.perform(get(bobBase + "/analyses/latest?lang=en").with(bob.bearer())).andExpect(status().isNotFound());

        // 3. Alice uploads the specification: private to her organization, the analysis becomes stale.
        MockMultipartFile swz = new MockMultipartFile("file", "SWZ.txt", "text/plain",
                "Rozdzial 7. Wadium w wysokosci 50 000 EUR. Termin zwiazania oferta 90 dni.".getBytes(
                        StandardCharsets.UTF_8));
        mvc.perform(multipart(aliceBase + "/documents").file(swz).with(alice.bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].scope").value("ORGANIZATION"))
                .andExpect(jsonPath("$[0].extractionStatus").value("DONE"))
                .andExpect(jsonPath("$[0].mimeType").value("text/plain"));
        mvc.perform(get(bobBase + "/documents").with(bob.bearer())).andExpect(jsonPath("$").isEmpty());
        assertThat(latest(alice, aliceOrg).get("stale").asBoolean()).isTrue();
        assertThat(latest(bob, bobOrg).get("stale").asBoolean()).isFalse();

        mvc.perform(post(aliceBase + "/analyses?lang=pl").with(alice.bearer())).andExpect(status().isAccepted());
        JsonNode privateDone = awaitDone(alice, aliceOrg);
        assertThat(privateDone.get("scope").asString()).isEqualTo("ORGANIZATION");
        assertThat(claude.calls).hasSize(2);
        assertThat(claude.calls.get(1).documents()).hasSize(2);
        JsonNode security = privateDone.get("result").get("sections").get(6);
        assertThat(security.get("key").asString()).isEqualTo("SECURITY");
        assertThat(security.get("findings").get(0).get("sources").get(0).get("documentTitle").asString())
                .isEqualTo("SWZ.txt");
        // Bob still sees only the public analysis.
        assertThat(latest(bob, bobOrg).get("scope").asString()).isEqualTo("PUBLIC");

        // 4. FREE plan: 2 analyses per month. A third new document set is refused.
        mvc.perform(multipart(aliceBase + "/documents").with(alice.bearer()).file(new MockMultipartFile("file",
                "zalacznik.txt", "text/plain", "Zalacznik nr 2 - formularz ofertowy, wzor umowy i kary umowne."
                        .getBytes(StandardCharsets.UTF_8)))).andExpect(status().isCreated());
        mvc.perform(post(aliceBase + "/analyses?lang=pl").with(alice.bearer()))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("ai_quota_exceeded"))
                .andExpect(jsonPath("$.limit").value(2));
        assertThat(claude.calls).hasSize(2);

        // Usage and cost are recorded per organization.
        assertThat(jdbc.queryForObject("select sum(cost_usd) from ai_usage where organization_id = ?::uuid",
                java.math.BigDecimal.class, aliceOrg)).isEqualByComparingTo("0.54");
    }

    @Test
    void failuresAreRefundedAndMissingConfigurationIsReported() throws Exception {
        Session carol = api.registerVerifyLogin("carol-" + System.nanoTime() + "@ai.test");
        String org = org(carol);
        String base = "/api/v1/organizations/" + org + "/tenders/" + T1;
        // Each test starts from fresh tender fixtures, so earlier public analyses are gone.
        claude.configured = false;
        mvc.perform(post(base + "/analyses?lang=pl").with(carol.bearer()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ai_not_configured"));
        claude.configured = true;
        claude.responder = docs -> {
            throw new ClaudeGateway.GatewayException("ai_rate_limited", "busy", null);
        };
        mvc.perform(post(base + "/analyses?lang=pl").with(carol.bearer())).andExpect(status().isAccepted());
        JsonNode failed = awaitDone(carol, org);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("errorCode").asString()).isEqualTo("ai_rate_limited");
        mvc.perform(get("/api/v1/organizations/" + org + "/ai/quota").with(carol.bearer()))
                .andExpect(jsonPath("$.used").value(0));
        // A failed analysis can be retried.
        claude.reset();
        mvc.perform(post(base + "/analyses?lang=pl").with(carol.bearer())).andExpect(status().isAccepted());
        assertThat(awaitDone(carol, org).get("status").asString()).isEqualTo("DONE");

        // Refusals are not charged either.
        mvc.perform(multipart(base + "/documents").with(carol.bearer()).file(new MockMultipartFile("file", "a.txt",
                "text/plain", "Specyfikacja warunkow zamowienia dla budowy szkoly.".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isCreated());
        claude.responder = docs -> new ClaudeGateway.Response("claude-sonnet-5-5", "refusal", java.util.List.of(),
                new ClaudeGateway.Usage(1000, 10, 0, 0));
        mvc.perform(post(base + "/analyses?lang=pl").with(carol.bearer())).andExpect(status().isAccepted());
        JsonNode refused = awaitDone(carol, org);
        assertThat(refused.get("errorCode").asString()).isEqualTo("ai_refused");
        mvc.perform(get("/api/v1/organizations/" + org + "/ai/quota").with(carol.bearer()))
                .andExpect(jsonPath("$.used").value(1));
    }

    @Test
    void documentsAreValidatedUnpackedAndIsolated() throws Exception {
        Session dave = api.registerVerifyLogin("dave-" + System.nanoTime() + "@ai.test");
        Session eve = api.registerVerifyLogin("eve-" + System.nanoTime() + "@ai.test");
        String org = org(dave);
        String base = "/api/v1/organizations/" + org + "/tenders/" + T1 + "/documents";

        mvc.perform(multipart(base).with(dave.bearer()).file(new MockMultipartFile("file", "setup.pdf",
                "application/pdf", new byte[] {'M', 'Z', 0, 1, 2, 3, 4})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unsupported_file"));
        mvc.perform(multipart(base).with(dave.bearer()).file(new MockMultipartFile("file", "empty.txt",
                "text/plain", new byte[0]))).andExpect(status().isBadRequest());

        byte[] textPdf = pdf("Specification of conditions of contract. Bid bond 50 000 EUR. Deadline 10 December 2026.");
        byte[] archive = zip("SWZ.pdf", textPdf, "formularz.txt",
                "Formularz ofertowy - cena brutto, termin realizacji.".getBytes(StandardCharsets.UTF_8));
        JsonNode stored = api.readJson(mvc.perform(multipart(base).with(dave.bearer())
                        .file(new MockMultipartFile("file", "dokumenty.zip", "application/zip", archive)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).get("fileName").asString()).isEqualTo("SWZ.pdf");
        assertThat(stored.get(0).get("mimeType").asString()).isEqualTo("application/pdf");
        assertThat(stored.get(0).get("extractionStatus").asString()).isEqualTo("DONE");
        assertThat(stored.get(0).get("pageCount").asInt()).isEqualTo(1);
        // The same file again is deduplicated.
        mvc.perform(multipart(base).with(dave.bearer()).file(new MockMultipartFile("file", "SWZ.pdf",
                "application/pdf", textPdf))).andExpect(status().isCreated());
        mvc.perform(get(base).with(dave.bearer())).andExpect(jsonPath("$.length()").value(2));

        // A scan without a text layer cannot be cited – reported, never analysed.
        mvc.perform(multipart(base).with(dave.bearer()).file(new MockMultipartFile("file", "skan.pdf",
                "application/pdf", pdf(null))))
                .andExpect(jsonPath("$[0].extractionStatus").value("NO_TEXT_LAYER"));

        // Isolation: another organization neither sees nor deletes them.
        String docId = stored.get(1).get("id").asString();
        mvc.perform(get(base).with(eve.bearer())).andExpect(status().isNotFound());
        String eveBase = "/api/v1/organizations/" + org(eve) + "/tenders/" + T1 + "/documents";
        mvc.perform(delete(eveBase + "/" + docId).with(eve.bearer())).andExpect(status().isNotFound());
        mvc.perform(delete(base + "/" + docId).with(dave.bearer())).andExpect(status().isNoContent());

        // The analysis sends the PDF as a PDF and the text file as text.
        mvc.perform(post("/api/v1/organizations/" + org + "/tenders/" + T1 + "/analyses?lang=en")
                .with(dave.bearer())).andExpect(status().isAccepted());
        await(() -> claude.calls.size() == 1);
        assertThat(claude.calls.getFirst().documents()).extracting(ClaudeGateway.DocumentInput::kind)
                .containsExactly(ClaudeGateway.Kind.TEXT, ClaudeGateway.Kind.PDF);

        // Public fetch: no link → 409; private network links are refused (SSRF protection).
        mvc.perform(post(base + "/fetch-public").with(dave.bearer()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("no_documents_link"));
        jdbc.update("update tenders set documents_url = 'http://127.0.0.1:9/swz.pdf' where id = ?::uuid", T1);
        mvc.perform(post(base + "/fetch-public").with(dave.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_ACCESSIBLE"))
                .andExpect(jsonPath("$.detail").value("PRIVATE_NETWORK"));
    }
}
