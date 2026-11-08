package eu.tenderintel.ai;

import eu.tenderintel.ai.AnalysisParser.Parsed;
import eu.tenderintel.ai.ClaudeGateway.DocumentInput;
import eu.tenderintel.ai.ClaudeGateway.GatewayException;
import eu.tenderintel.ai.ClaudeGateway.Kind;
import eu.tenderintel.ai.ClaudeGateway.Response;
import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.document.DocumentService;
import eu.tenderintel.document.DocumentService.UsableDocument;
import eu.tenderintel.document.TextExtractor;
import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.tender.TenderDtos.TenderDetail;
import eu.tenderintel.tender.TenderQueries;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI analysis of tender documents, on demand. An analysis of public documents is shared by all organizations (the
 * documents are public, the result is the same), so it is generated once and re-opening it costs nothing and does not
 * count towards the plan limit. When an organization uploaded its own files, its analysis is private to it.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);
    static final int MAX_TEXT_CHARS = 600_000;
    static final int MAX_PDF_PAGES = 550;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrganizationAccess access;
    private final DocumentService documents;
    private final TenderQueries tenders;
    private final ClaudeGateway claude;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;
    private final AppProperties.Ai props;
    private final ExecutorService executor;

    AnalysisService(JdbcTemplate jdbc, TransactionTemplate tx, OrganizationAccess access, DocumentService documents,
            TenderQueries tenders, ClaudeGateway claude, AuditService audit, JsonMapper json, Clock clock,
            AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.documents = documents;
        this.tenders = tenders;
        this.claude = claude;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.props = props.ai();
        this.executor = Executors.newFixedThreadPool(Math.max(1, this.props.workers()),
                Thread.ofPlatform().name("ai-analysis-", 0).daemon().factory());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public record AnalysisView(UUID id, String status, String language, String scope, String model,
            Instant createdAt, Instant completedAt, String errorCode, boolean stale, Object result) {
    }

    public record QuotaView(boolean configured, String planCode, int limit, long used, long remaining) {
    }

    /** What would be sent to the model, and documents left out because of request limits. */
    record Plan(String documentSetHash, UUID scopeOrgId, List<DocumentInput> inputs, List<Map<String, Object>> sent,
            List<Map<String, Object>> skipped) {
    }

    // --- API ----------------------------------------------------------------------------------------------------

    public QuotaView quota(UUID orgId, UUID userId) {
        access.requireMember(orgId, userId);
        String plan = planCode(orgId);
        int limit = eu.tenderintel.billing.Plan.of(plan).aiAnalysesPerMonth();
        long used = usedThisMonth(orgId);
        return new QuotaView(claude.configured(), plan, limit, used, Math.max(0, limit - used));
    }

    public AnalysisView latest(UUID orgId, UUID userId, UUID tenderId, String lang) {
        access.requireMember(orgId, userId);
        String language = language(lang);
        List<AnalysisView> rows = jdbc.query("""
                select id, status, language, organization_id, model, created_at, completed_at, error_code,
                       document_set_hash, result::text
                from ai_analyses
                where tender_id = ? and language = ? and prompt_version = ?
                  and (organization_id is null or organization_id = ?)
                order by created_at desc limit 1
                """, (rs, i) -> view(rs, null), tenderId, language, AnalysisPrompt.VERSION, orgId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "analysis_missing", "No analysis yet");
        }
        AnalysisView v = rows.getFirst();
        if (!"DONE".equals(v.status())) {
            return v;
        }
        // Metadata only: no document content is read just to tell whether the analysis is out of date.
        String current = plan(tenderId, orgId, language, false).documentSetHash();
        String stored = jdbc.queryForObject("select document_set_hash from ai_analyses where id = ?", String.class,
                v.id());
        return new AnalysisView(v.id(), v.status(), v.language(), v.scope(), v.model(), v.createdAt(),
                v.completedAt(), v.errorCode(), !current.equals(stored), v.result());
    }

    /** Starts an analysis, or returns the existing one for the same documents (free, not counted). */
    public AnalysisView request(UUID orgId, UUID userId, UUID tenderId, String lang) {
        access.requireMember(orgId, userId);
        if (!claude.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ai_not_configured",
                    "AI analysis is not configured on this server");
        }
        String language = language(lang);
        Plan meta = plan(tenderId, orgId, language, false);
        AnalysisView existing = find(tenderId, meta, language);
        if (existing != null) {
            return existing;
        }
        Plan plan = plan(tenderId, orgId, language, true);
        UUID analysisId = Ids.newId();
        UUID usageId = Ids.newId();
        AnalysisView created = tx.execute(s -> {
            String planCode = jdbc.queryForObject("select plan_code from organizations where id = ? for update",
                    String.class, orgId);
            AnalysisView again = find(tenderId, plan, language);
            if (again != null) {
                return again;
            }
            int limit = eu.tenderintel.billing.Plan.of(planCode).aiAnalysesPerMonth();
            long used = usedThisMonth(orgId);
            if (used >= limit) {
                throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "ai_quota_exceeded",
                        "Monthly AI analysis limit of the plan reached",
                        Map.of("limit", limit, "used", used, "plan", planCode));
            }
            jdbc.update("""
                    insert into ai_analyses (id, tender_id, organization_id, language, document_set_hash,
                        prompt_version, status, model, requested_by, requested_by_org)
                    values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                    """, analysisId, tenderId, plan.scopeOrgId(), language, plan.documentSetHash(),
                    AnalysisPrompt.VERSION, props.analysisModel(), userId, orgId);
            jdbc.update("""
                    insert into ai_usage (id, organization_id, user_id, feature, analysis_id, model)
                    values (?, ?, ?, 'DOCUMENT_ANALYSIS', ?, ?)
                    """, usageId, orgId, userId, analysisId, props.analysisModel());
            return null;
        });
        if (created != null) {
            return created;
        }
        audit.record(AuditAction.AI_ANALYSIS_REQUESTED, userId, orgId, "tender", tenderId,
                Map.of("analysisId", analysisId.toString(), "documents", plan.sent().size()));
        // Read before the worker starts: the response always reports the new analysis as PENDING (202).
        AnalysisView pending = byId(analysisId);
        executor.execute(() -> run(analysisId, usageId, plan, language));
        return pending;
    }

    // --- execution ----------------------------------------------------------------------------------------------

    void run(UUID analysisId, UUID usageId, Plan plan, String language) {
        jdbc.update("update ai_analyses set status = 'RUNNING', started_at = now() where id = ?", analysisId);
        try {
            Response r = claude.analyze(AnalysisPrompt.system(language), plan.inputs(), AnalysisPrompt.INSTRUCTION);
            BigDecimal cost = AiPricing.costUsd(r.model(), r.usage());
            boolean refused = "refusal".equals(r.stopReason());
            jdbc.update("""
                    update ai_usage set model = ?, input_tokens = ?, output_tokens = ?, cache_read_tokens = ?,
                        cache_creation_tokens = ?, cost_usd = ?, counts_to_quota = ?
                    where id = ?
                    """, r.model(), r.usage().inputTokens(), r.usage().outputTokens(), r.usage().cacheReadTokens(),
                    r.usage().cacheCreationTokens(), cost, !refused, usageId);
            if (refused) {
                fail(analysisId, "ai_refused");
                return;
            }
            Parsed parsed = AnalysisParser.parse(r.segments());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sections", sections(parsed, plan));
            result.put("documents", plan.sent());
            result.put("skippedDocuments", plan.skipped());
            result.put("droppedUncited", parsed.droppedUncited());
            result.put("truncated", "max_tokens".equals(r.stopReason()));
            jdbc.update("""
                    update ai_analyses set status = 'DONE', model = ?, result = ?::jsonb, completed_at = now()
                    where id = ?
                    """, r.model(), json.writeValueAsString(result), analysisId);
            log.info("AI analysis {} done: {} in / {} out tokens, {} USD, {} uncited findings dropped", analysisId,
                    r.usage().inputTokens(), r.usage().outputTokens(), cost, parsed.droppedUncited());
        } catch (GatewayException e) {
            log.warn("AI analysis {} failed: {} ({})", analysisId, e.code(), e.getMessage());
            refund(usageId);
            fail(analysisId, e.code());
        } catch (RuntimeException e) {
            log.error("AI analysis {} failed", analysisId, e);
            refund(usageId);
            fail(analysisId, "ai_error");
        }
    }

    private List<Map<String, Object>> sections(Parsed parsed, Plan plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnalysisParser.Section s : parsed.sections()) {
            List<Map<String, Object>> findings = new ArrayList<>();
            for (AnalysisParser.Finding f : s.findings()) {
                List<Map<String, Object>> sources = new ArrayList<>();
                for (AnalysisParser.SourceRef ref : f.sources()) {
                    Map<String, Object> doc = ref.documentIndex() < plan.sent().size()
                            ? plan.sent().get(ref.documentIndex()) : Map.of();
                    Map<String, Object> src = new LinkedHashMap<>();
                    src.put("documentIndex", ref.documentIndex());
                    src.put("documentId", doc.get("id"));
                    src.put("documentTitle", doc.get("title"));
                    src.put("quote", ref.citedText());
                    src.put("pageFrom", ref.startPage());
                    src.put("pageTo", ref.endPage());
                    sources.add(src);
                }
                findings.add(Map.of("text", f.text(), "cited", f.cited(), "sources", sources));
            }
            out.add(Map.of("key", s.key(), "notFound", s.notFound(), "findings", findings));
        }
        return out;
    }

    private void fail(UUID analysisId, String code) {
        jdbc.update("""
                update ai_analyses set status = 'FAILED', error_code = ?, completed_at = now() where id = ?
                """, code, analysisId);
    }

    private void refund(UUID usageId) {
        jdbc.update("update ai_usage set counts_to_quota = false where id = ?", usageId);
    }

    /**
     * Analyses abandoned by a crashed or restarted instance (no result long after the request timeout) are failed and
     * refunded so users can run them again. Age-based, so analyses running on other instances are left alone.
     */
    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT15M")
    void failAbandoned() {
        Timestamp cutoff = Timestamp.from(Instant.now(clock).minus(props.requestTimeout().multipliedBy(3)));
        jdbc.update("""
                update ai_usage set counts_to_quota = false
                where analysis_id in (select id from ai_analyses
                                      where status in ('PENDING', 'RUNNING') and created_at < ?)
                """, cutoff);
        int n = jdbc.update("""
                update ai_analyses set status = 'FAILED', error_code = 'interrupted', completed_at = now()
                where status in ('PENDING', 'RUNNING') and created_at < ?
                """, cutoff);
        if (n > 0) {
            log.warn("Marked {} abandoned AI analyses as failed", n);
        }
    }

    // --- planning -----------------------------------------------------------------------------------------------

    /**
     * Chooses the documents of an analysis within the request limits (by stored size, page and character counts) and
     * computes the document-set hash. Content is read only when {@code loadContent} – the hash never needs it.
     */
    Plan plan(UUID tenderId, UUID orgId, String language, boolean loadContent) {
        TenderDetail tender = tenders.get(tenderId, language);
        String notice = NoticeDocument.text(tender);
        // BZP notices carry the full official text (conditions, criteria, guarantees): include it, in order.
        List<String> bzpTexts = jdbc.queryForList("""
                select r.payload->>'htmlBody' from tender_sources s
                join data_sources d on d.id = s.data_source_id and d.code = 'PL_EZAMOWIENIA'
                join raw_notices r on r.data_source_id = s.data_source_id and r.external_id = s.external_id
                where s.tender_id = ? and r.payload->>'htmlBody' is not null
                order by s.first_seen_at, r.fetched_at
                """, String.class, tenderId);
        if (!bzpTexts.isEmpty()) {
            StringBuilder full = new StringBuilder(notice).append("\n\n");
            for (String html : bzpTexts) {
                full.append("\n\n").append(eu.tenderintel.ingestion.bzp.BzpNoticeText.plainText(html));
            }
            notice = full.toString().strip();
            if (notice.length() > MAX_TEXT_CHARS / 2) {
                notice = notice.substring(0, MAX_TEXT_CHARS / 2);
            }
        }
        List<UsableDocument> usable = documents.usable(tenderId, orgId);
        boolean orgScope = usable.stream().anyMatch(UsableDocument::organizationScoped);

        List<DocumentInput> inputs = new ArrayList<>();
        List<Map<String, Object>> sent = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        inputs.add(new DocumentInput(NoticeDocument.title(language), Kind.TEXT, notice));
        sent.add(docInfo(null, NoticeDocument.title(language), "NOTICE"));

        long pdfBudget = props.maxPdfMbPerRequest() * 1024L * 1024L;
        int pages = 0;
        int chars = notice.length();
        StringBuilder hash = new StringBuilder("notice:").append(sha256(notice));
        for (UsableDocument d : usable) {
            if (TextExtractor.PDF.equals(d.mimeType())) {
                int p = d.pageCount() == null ? 1 : d.pageCount();
                if (d.sizeBytes() > pdfBudget || pages + p > MAX_PDF_PAGES) {
                    skipped.add(docInfo(d.id(), d.fileName(), "REQUEST_LIMIT"));
                    continue;
                }
                pdfBudget -= d.sizeBytes();
                pages += p;
                if (loadContent) {
                    inputs.add(new DocumentInput(d.fileName(), Kind.PDF,
                            Base64.getEncoder().encodeToString(documents.content(d.sha256()))));
                }
            } else {
                if (d.textChars() == 0 || chars + d.textChars() > MAX_TEXT_CHARS) {
                    skipped.add(docInfo(d.id(), d.fileName(), "REQUEST_LIMIT"));
                    continue;
                }
                chars += d.textChars();
                if (loadContent) {
                    inputs.add(new DocumentInput(d.fileName(), Kind.TEXT, documents.text(d.id())));
                }
            }
            sent.add(docInfo(d.id(), d.fileName(), d.organizationScoped() ? "ORGANIZATION" : "PUBLIC"));
            hash.append('|').append(d.sha256());
        }
        return new Plan(sha256(hash.toString()), orgScope ? orgId : null, inputs, sent, skipped);
    }

    private static Map<String, Object> docInfo(UUID id, String title, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id == null ? null : id.toString());
        m.put("title", title);
        m.put("kind", kind);
        return m;
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private AnalysisView find(UUID tenderId, Plan plan, String language) {
        List<AnalysisView> rows = jdbc.query("""
                select id, status, language, organization_id, model, created_at, completed_at, error_code,
                       document_set_hash, result::text
                from ai_analyses
                where tender_id = ? and language = ? and document_set_hash = ? and prompt_version = ?
                  and organization_id is not distinct from ?::uuid and status <> 'FAILED'
                """, (rs, i) -> view(rs, null), tenderId, language, plan.documentSetHash(), AnalysisPrompt.VERSION,
                plan.scopeOrgId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private AnalysisView byId(UUID id) {
        return jdbc.queryForObject("""
                select id, status, language, organization_id, model, created_at, completed_at, error_code,
                       document_set_hash, result::text
                from ai_analyses where id = ?
                """, (rs, i) -> view(rs, null), id);
    }

    private AnalysisView view(java.sql.ResultSet rs, Object unused) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp completed = rs.getTimestamp("completed_at");
        String result = rs.getString("result");
        return new AnalysisView(rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("language"),
                rs.getObject("organization_id") == null ? "PUBLIC" : "ORGANIZATION", rs.getString("model"),
                created == null ? null : created.toInstant(), completed == null ? null : completed.toInstant(),
                rs.getString("error_code"), false, result == null ? null : json.readValue(result, Object.class));
    }

    private String planCode(UUID orgId) {
        return jdbc.queryForObject("select plan_code from organizations where id = ?", String.class, orgId);
    }

    long usedThisMonth(UUID orgId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Long n = jdbc.queryForObject("""
                select count(*) from ai_usage
                where organization_id = ? and feature = 'DOCUMENT_ANALYSIS' and counts_to_quota and created_at >= ?
                """, Long.class, orgId, Timestamp.from(monthStart));
        return n == null ? 0 : n;
    }

    static String language(String lang) {
        return "pl".equalsIgnoreCase(lang) ? "pl" : "en";
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
