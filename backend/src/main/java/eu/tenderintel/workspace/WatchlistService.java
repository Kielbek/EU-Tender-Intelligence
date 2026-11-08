package eu.tenderintel.workspace;

import eu.tenderintel.common.error.ApiException;
import eu.tenderintel.common.id.Ids;
import eu.tenderintel.organization.OrganizationAccess;
import eu.tenderintel.tender.TenderDtos.TenderSummary;
import eu.tenderintel.tender.TenderQueries;
import eu.tenderintel.workspace.WorkspaceDtos.SaveTenderRequest;
import eu.tenderintel.workspace.WorkspaceDtos.SavedTenderView;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenders the organization follows, with a simple bid pipeline status and shared notes. */
@Service
public class WatchlistService {

    private final JdbcTemplate jdbc;
    private final OrganizationAccess access;
    private final TenderQueries tenders;

    WatchlistService(JdbcTemplate jdbc, OrganizationAccess access, TenderQueries tenders) {
        this.jdbc = jdbc;
        this.access = access;
        this.tenders = tenders;
    }

    record Row(UUID tenderId, String status, String notes, String createdByName, Timestamp createdAt,
            Timestamp updatedAt) {
    }

    @Transactional(readOnly = true)
    public List<SavedTenderView> list(UUID orgId, UUID userId, String status, String lang) {
        access.requireMember(orgId, userId);
        List<Row> rows = jdbc.query("""
                select s.tender_id, s.status, s.notes, u.full_name, s.created_at, s.updated_at
                from saved_tenders s left join users u on u.id = s.created_by
                where s.organization_id = ? and (cast(? as text) is null or s.status = cast(? as text))
                order by s.updated_at desc
                limit 1000
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5), rs.getTimestamp(6)), orgId, status, status);
        Map<UUID, TenderSummary> summaries = tenders.summaries(rows.stream().map(Row::tenderId).toList(), lang);
        List<SavedTenderView> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(view(r, summaries.get(r.tenderId())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SavedTenderView get(UUID orgId, UUID userId, UUID tenderId, String lang) {
        access.requireMember(orgId, userId);
        Row row = find(orgId, tenderId);
        if (row == null) {
            throw ApiException.notFound("Saved tender");
        }
        return view(row, tenders.summaries(List.of(tenderId), lang).get(tenderId));
    }

    /** Adds the tender to the watch-list or updates its status/notes (idempotent). */
    @Transactional
    public SavedTenderView save(UUID orgId, UUID userId, UUID tenderId, SaveTenderRequest req, String lang) {
        access.requireMember(orgId, userId);
        Integer exists = jdbc.queryForObject("select count(*) from tenders where id = ?", Integer.class, tenderId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("Tender");
        }
        jdbc.update("""
                insert into saved_tenders (id, organization_id, tender_id, created_by, status, notes)
                values (?, ?, ?, ?, coalesce(?, 'WATCHING'), ?)
                on conflict (organization_id, tender_id) do update set
                    status = coalesce(cast(? as text), saved_tenders.status),
                    notes = case when ? then excluded.notes else saved_tenders.notes end,
                    updated_at = now()
                """, Ids.newId(), orgId, tenderId, userId, req.status(), emptyToNull(req.notes()), req.status(),
                req.notes() != null);
        return get(orgId, userId, tenderId, lang);
    }

    @Transactional
    public void remove(UUID orgId, UUID userId, UUID tenderId) {
        access.requireMember(orgId, userId);
        jdbc.update("delete from saved_tenders where organization_id = ? and tender_id = ?", orgId, tenderId);
    }

    private Row find(UUID orgId, UUID tenderId) {
        List<Row> rows = jdbc.query("""
                select s.tender_id, s.status, s.notes, u.full_name, s.created_at, s.updated_at
                from saved_tenders s left join users u on u.id = s.created_by
                where s.organization_id = ? and s.tender_id = ?
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5), rs.getTimestamp(6)), orgId, tenderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static SavedTenderView view(Row r, TenderSummary summary) {
        return new SavedTenderView(r.tenderId(), r.status(), r.notes(), r.createdByName(), r.createdAt().toInstant(),
                r.updatedAt().toInstant(), summary);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
