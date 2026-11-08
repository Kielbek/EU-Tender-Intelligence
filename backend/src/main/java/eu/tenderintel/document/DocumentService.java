package eu.tenderintel.document;

import eu.tenderintel.audit.AuditAction;
import eu.tenderintel.audit.AuditService;
import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.document.DocumentDtos.DocumentView;
import eu.tenderintel.document.DocumentDtos.FetchResult;
import eu.tenderintel.document.PublicDocumentFetcher.Fetched;
import eu.tenderintel.document.PublicDocumentFetcher.Outcome;
import eu.tenderintel.organization.OrganizationAccess;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tender documents: public files fetched from the notice's documents link (shared) and files uploaded by an
 * organization (private to it). Contents are untrusted data – they are stored, measured and passed to the AI only as
 * data, never interpreted as instructions.
 */
@Service
public class DocumentService {

    /** A public fetch is not repeated more often than this, to be gentle with procurement portals. */
    static final java.time.Duration REFETCH_AFTER = java.time.Duration.ofHours(6);
    private static final String COLUMNS = """
            id, file_name, mime_type, size_bytes, source, organization_id, fetch_status, extraction_status,
            page_count, text_chars, error, created_at""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrganizationAccess access;
    private final TextExtractor extractor;
    private final PublicDocumentFetcher fetcher;
    private final AuditService audit;
    private final AppProperties.Ai ai;

    DocumentService(JdbcTemplate jdbc, TransactionTemplate tx, OrganizationAccess access, TextExtractor extractor,
            PublicDocumentFetcher fetcher, AuditService audit, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.access = access;
        this.extractor = extractor;
        this.fetcher = fetcher;
        this.audit = audit;
        this.ai = props.ai();
    }

    /** A document usable for analysis: a PDF (sent as PDF) or extracted text. */
    public record UsableDocument(UUID id, String fileName, String mimeType, String sha256, boolean organizationScoped,
            Integer pageCount) {
    }

    long maxBytes() {
        return ai.maxUploadMb() * 1024L * 1024L;
    }

    public List<DocumentView> list(UUID orgId, UUID userId, UUID tenderId) {
        access.requireMember(orgId, userId);
        requireTender(tenderId);
        return jdbc.query("select " + COLUMNS + """
                 from tender_documents
                where tender_id = ? and (organization_id is null or organization_id = ?)
                order by organization_id nulls first, created_at, file_name
                """, DocumentService::view, tenderId, orgId);
    }

    public List<DocumentView> upload(UUID orgId, UUID userId, UUID tenderId, String fileName, byte[] content) {
        access.requireMember(orgId, userId);
        requireTender(tenderId);
        if (content == null || content.length == 0) {
            throw ApiException.badRequest("empty_file", "The file is empty");
        }
        if (content.length > maxBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "file_too_large",
                    "The file exceeds " + ai.maxUploadMb() + " MB", Map.of("maxMb", ai.maxUploadMb()));
        }
        String name = TextExtractor.baseName(fileName == null ? "document" : fileName);
        String mime = extractor.detect(content, name);
        List<TextExtractor.Entry> entries;
        if (extractor.isZip(mime)) {
            try {
                entries = extractor.unzip(content, maxBytes());
            } catch (IOException e) {
                throw ApiException.badRequest("invalid_archive", "The archive could not be read");
            }
            if (entries.isEmpty()) {
                throw ApiException.badRequest("unsupported_file", "The archive contains no supported documents");
            }
        } else if (extractor.isSupported(mime)) {
            entries = List.of(new TextExtractor.Entry(name, content));
        } else {
            throw ApiException.badRequest("unsupported_file",
                    "Supported: PDF, DOCX, DOC, ODT, RTF, TXT, XLSX, XLS, ODS and ZIP archives with them");
        }
        List<DocumentView> stored = new ArrayList<>();
        for (TextExtractor.Entry e : entries) {
            stored.add(store(tenderId, orgId, userId, e.fileName(), e.content(), "UPLOAD", null));
        }
        audit.record(AuditAction.TENDER_DOCUMENT_UPLOADED, userId, orgId, "tender", tenderId,
                Map.of("files", stored.size()));
        return stored;
    }

    public void delete(UUID orgId, UUID userId, UUID tenderId, UUID documentId) {
        access.requireMember(orgId, userId);
        int n = jdbc.update("delete from tender_documents where id = ? and tender_id = ? and organization_id = ?",
                documentId, tenderId, orgId);
        if (n == 0) {
            throw ApiException.notFound("Document");
        }
        audit.record(AuditAction.TENDER_DOCUMENT_DELETED, userId, orgId, "tender_document", documentId, Map.of());
    }

    /** Fetches the documents link published in the notice, if it points directly at a public file. */
    public FetchResult fetchPublic(UUID orgId, UUID userId, UUID tenderId) {
        access.requireMember(orgId, userId);
        List<String> urls = jdbc.queryForList("select documents_url from tenders where id = ?", String.class, tenderId);
        if (urls.isEmpty()) {
            throw ApiException.notFound("Tender");
        }
        String url = urls.getFirst();
        if (url == null || url.isBlank()) {
            throw ApiException.conflict("no_documents_link", "The notice does not publish a documents link");
        }
        List<Map<String, Object>> recent = jdbc.queryForList("""
                select fetch_status, error from tender_documents
                where tender_id = ? and organization_id is null and source = 'PORTAL' and updated_at > ?
                order by updated_at desc limit 1
                """, tenderId, Timestamp.from(java.time.Instant.now().minus(REFETCH_AFTER)));
        if (!recent.isEmpty()) {
            return new FetchResult((String) recent.getFirst().get("fetch_status"),
                    (String) recent.getFirst().get("error"), list(orgId, userId, tenderId));
        }
        Fetched f = fetcher.fetch(url, maxBytes());
        if (f.outcome() != Outcome.FETCHED) {
            recordFailedFetch(tenderId, url, f);
            return new FetchResult(f.outcome().name(), f.detail(), list(orgId, userId, tenderId));
        }
        String mime = extractor.detect(f.content(), f.fileName());
        List<TextExtractor.Entry> entries;
        try {
            entries = extractor.isZip(mime) ? extractor.unzip(f.content(), maxBytes())
                    : extractor.isSupported(mime) ? List.of(new TextExtractor.Entry(f.fileName(), f.content()))
                    : List.of();
        } catch (IOException e) {
            entries = List.of();
        }
        if (entries.isEmpty()) {
            Fetched unsupported = new Fetched(Outcome.NOT_ACCESSIBLE, null, null, "UNSUPPORTED");
            recordFailedFetch(tenderId, url, unsupported);
            return new FetchResult(Outcome.NOT_ACCESSIBLE.name(), unsupported.detail(), list(orgId, userId, tenderId));
        }
        for (TextExtractor.Entry e : entries) {
            store(tenderId, null, null, e.fileName(), e.content(), "PORTAL", url);
        }
        jdbc.update("""
                delete from tender_documents where tender_id = ? and organization_id is null and source = 'PORTAL'
                  and sha256 is null
                """, tenderId);
        return new FetchResult(Outcome.FETCHED.name(), null, list(orgId, userId, tenderId));
    }

    private void recordFailedFetch(UUID tenderId, String url, Fetched f) {
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    delete from tender_documents where tender_id = ? and organization_id is null and source = 'PORTAL'
                      and sha256 is null
                    """, tenderId);
            jdbc.update("""
                    insert into tender_documents (id, tender_id, source_url, source, fetch_status, extraction_status,
                        error)
                    values (?, ?, ?, 'PORTAL', ?, 'SKIPPED', ?)
                    """, Ids.newId(), tenderId, url, f.outcome().name(), f.detail());
        });
    }

    DocumentView store(UUID tenderId, UUID orgId, UUID userId, String fileName, byte[] content, String source,
            String sourceUrl) {
        String sha = sha256(content);
        String mime = extractor.detect(content, fileName);
        TextExtractor.Extraction ex = extractor.extract(content, mime);
        return tx.execute(s -> {
            jdbc.update("""
                    insert into document_blobs (sha256, content, size_bytes) values (?, ?, ?)
                    on conflict (sha256) do nothing
                    """, sha, content, content.length);
            UUID id = Ids.newId();
            jdbc.update("""
                    insert into tender_documents (id, tender_id, source_url, file_name, mime_type, size_bytes, sha256,
                        storage_key, fetch_status, extraction_status, page_count, source, organization_id, uploaded_by,
                        extracted_text, text_chars, error)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'FETCHED', ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tender_id, coalesce(organization_id, '00000000-0000-0000-0000-000000000000'::uuid),
                                 sha256) where sha256 is not null
                    do update set updated_at = now()
                    """, id, tenderId, sourceUrl, fileName, mime, (long) content.length, sha, "db:" + sha,
                    ex.status(), ex.pageCount(), source, orgId, userId, ex.text(),
                    ex.text() == null ? null : ex.text().length(), ex.error());
            return jdbc.queryForObject("select " + COLUMNS + """
                     from tender_documents
                    where tender_id = ? and sha256 = ?
                      and coalesce(organization_id, '00000000-0000-0000-0000-000000000000'::uuid)
                          = coalesce(?::uuid, '00000000-0000-0000-0000-000000000000'::uuid)
                    """, DocumentService::view, tenderId, sha, orgId);
        });
    }

    /** Documents of a tender the analysis can read: public ones and those of the given organization. */
    public List<UsableDocument> usable(UUID tenderId, UUID orgId) {
        return jdbc.query("""
                select id, file_name, mime_type, sha256, organization_id is not null, page_count
                from tender_documents
                where tender_id = ? and (organization_id is null or organization_id = ?)
                  and extraction_status = 'DONE' and sha256 is not null
                order by organization_id nulls first, created_at, file_name
                """, (rs, i) -> new UsableDocument(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5), (Integer) rs.getObject(6)), tenderId, orgId);
    }

    public byte[] content(String sha256) {
        return jdbc.queryForObject("select content from document_blobs where sha256 = ?", byte[].class, sha256);
    }

    public String text(UUID documentId) {
        return jdbc.queryForObject("select extracted_text from tender_documents where id = ?", String.class,
                documentId);
    }

    private void requireTender(UUID tenderId) {
        Integer n = jdbc.queryForObject("select count(*) from tenders where id = ?", Integer.class, tenderId);
        if (n == null || n == 0) {
            throw ApiException.notFound("Tender");
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentView view(ResultSet rs, int i) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new DocumentView(rs.getObject("id", UUID.class), rs.getString("file_name"), rs.getString("mime_type"),
                (Long) rs.getObject("size_bytes"), rs.getString("source"),
                rs.getObject("organization_id") == null ? "PUBLIC" : "ORGANIZATION", rs.getString("fetch_status"),
                rs.getString("extraction_status"), (Integer) rs.getObject("page_count"),
                (Integer) rs.getObject("text_chars"), rs.getString("error"),
                created == null ? null : created.toInstant());
    }
}
