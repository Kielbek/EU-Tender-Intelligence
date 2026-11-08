package eu.tenderintel.notification;

import eu.tenderintel.tender.TenderDtos.TenderSearchCriteria;
import eu.tenderintel.tender.TenderQueries;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns what happened in the data into notifications, each step as one set-based statement. Recipients are the
 * active members of the organization; the type's preferences (or its defaults) decide in-app/e-mail. A unique
 * {@code (user, dedup_key)} guarantees that re-scoring or re-scanning never notifies twice, which lets every scan look
 * back a little further than the last one (rows written by long transactions become visible late).
 */
@Component
class NotificationGenerator {

    /** Looking back this much before the cursor catches rows committed after the previous scan read past them. */
    static final Duration OVERLAP = Duration.ofMinutes(15);
    /** ...and published within this many days (a backfill of old notices is not news either). */
    static final int RECENT_PUBLICATION_DAYS = 14;
    /** Diff fields of a watched tender that are worth a notification (title or description edits are not). */
    static final String[] WATCHED_FIELDS = {"submissionDeadline", "status", "estimatedValue", "currency",
            "documentsUrl"};
    private static final Logger log = LoggerFactory.getLogger(NotificationGenerator.class);

    private static final String RECIPIENTS = """
            join organization_members om on om.organization_id = %1$s
            join users u on u.id = om.user_id and u.status = 'ACTIVE'
            left join notification_preferences p on p.user_id = om.user_id and p.organization_id = %1$s
                 and p.event_type = '%2$s'
            """;
    private static final String DELIVERY = """
            coalesce(p.in_app, %1$s), coalesce(p.email_frequency, '%2$s')
            """;
    private static final String WANTED = """
            (coalesce(p.in_app, %1$s) or coalesce(p.email_frequency, '%2$s') <> 'OFF')
            """;

    private final JdbcTemplate jdbc;
    private final TenderQueries tenders;
    private final JsonMapper json;

    NotificationGenerator(JdbcTemplate jdbc, TenderQueries tenders, JsonMapper json) {
        this.jdbc = jdbc;
        this.tenders = tenders;
        this.json = json;
    }

    record Counts(int newMatches, int watchedChanges, int deadlines, int savedSearches) {
        int total() {
            return newMatches + watchedChanges + deadlines + savedSearches;
        }
    }

    Counts generate(Instant from, Instant to, int reminderDays) {
        return new Counts(newMatches(from, to), watchedChanges(from, to), deadlines(to, reminderDays),
                savedSearches(to));
    }

    int newMatches(Instant from, Instant to) {
        NotificationType t = NotificationType.NEW_MATCH;
        return jdbc.update("""
                insert into notifications (id, user_id, organization_id, type, tender_id, payload, in_app,
                    email_frequency, dedup_key)
                select gen_random_uuid(), om.user_id, m.organization_id, 'NEW_MATCH', m.tender_id,
                       jsonb_build_object('score', m.score), %s,
                       'NEW_MATCH:' || m.organization_id || ':' || m.tender_id
                from tender_matches m
                join tenders t on t.id = m.tender_id
                %s
                where m.computed_at > ? and m.computed_at <= ?
                  and t.status = 'OPEN'
                  -- Only tenders that are new since the last scan: matches recomputed after a profile change
                  -- (thousands of existing tenders) are on the matches page, not in notifications.
                  and t.created_at > ? and t.published_at > ?
                  and m.score >= coalesce(p.min_score, %d)
                  and %s
                on conflict (user_id, dedup_key) do nothing
                """.formatted(delivery(t), recipients("m.organization_id", t), t.defaultMinScore(), wanted(t)),
                ts(from.minus(OVERLAP)), ts(to), ts(from.minus(OVERLAP)),
                ts(to.minus(Duration.ofDays(RECENT_PUBLICATION_DAYS))));
    }

    int watchedChanges(Instant from, Instant to) {
        NotificationType t = NotificationType.WATCHED_CHANGED;
        return jdbc.update("""
                insert into notifications (id, user_id, organization_id, type, tender_id, payload, in_app,
                    email_frequency, dedup_key)
                select gen_random_uuid(), om.user_id, s.organization_id, 'WATCHED_CHANGED', v.tender_id,
                       jsonb_build_object('versionNo', v.version_no, 'changeKind', v.change_kind,
                           'changes', coalesce((select jsonb_agg(d) from jsonb_array_elements(v.diff) d
                                                where d->>'field' = any (?)), '[]'::jsonb)),
                       %s,
                       'WATCHED:' || s.organization_id || ':' || v.id
                from tender_versions v
                join saved_tenders s on s.tender_id = v.tender_id
                     and s.status in ('WATCHING', 'PREPARING', 'SUBMITTED')
                %s
                where v.created_at > ? and v.created_at <= ? and v.version_no > 1
                  and v.created_at > s.created_at
                  and (v.change_kind in ('CANCELLATION', 'AWARD')
                       or exists (select 1 from jsonb_array_elements(v.diff) d where d->>'field' = any (?)))
                  and %s
                on conflict (user_id, dedup_key) do nothing
                """.formatted(delivery(t), recipients("s.organization_id", t), wanted(t)),
                WATCHED_FIELDS, ts(from.minus(OVERLAP)), ts(to), WATCHED_FIELDS);
    }

    int deadlines(Instant now, int reminderDays) {
        NotificationType t = NotificationType.DEADLINE_APPROACHING;
        return jdbc.update("""
                insert into notifications (id, user_id, organization_id, type, tender_id, payload, in_app,
                    email_frequency, dedup_key)
                select gen_random_uuid(), om.user_id, s.organization_id, 'DEADLINE_APPROACHING', t.id,
                       jsonb_build_object('deadline', t.submission_deadline), %s,
                       'DEADLINE:' || s.organization_id || ':' || t.id || ':'
                           || extract(epoch from t.submission_deadline)::bigint
                from saved_tenders s
                join tenders t on t.id = s.tender_id
                %s
                where s.status in ('WATCHING', 'PREPARING') and t.status = 'OPEN'
                  and t.submission_deadline > ? and t.submission_deadline <= ?
                  and %s
                on conflict (user_id, dedup_key) do nothing
                """.formatted(delivery(t), recipients("s.organization_id", t), wanted(t)),
                ts(now), ts(now.plus(Duration.ofDays(reminderDays))));
    }

    /** One notification per saved search and scan with the number of new tenders and a few examples. */
    int savedSearches(Instant to) {
        List<Map<String, Object>> alerts = jdbc.queryForList("""
                select s.id, s.organization_id, s.user_id, s.name, s.filters::text as filters,
                       coalesce(s.alert_frequency, 'DAILY') as frequency,
                       coalesce(s.alert_checked_at, s.updated_at) as since
                from saved_searches s
                join organization_members om on om.organization_id = s.organization_id and om.user_id = s.user_id
                join users u on u.id = s.user_id and u.status = 'ACTIVE'
                where s.alert_enabled
                """);
        int created = 0;
        for (Map<String, Object> a : alerts) {
            UUID id = (UUID) a.get("id");
            Instant since = ((Timestamp) a.get("since")).toInstant();
            if (!since.isBefore(to)) {
                continue;
            }
            try {
                TenderSearchCriteria criteria = json.readValue((String) a.get("filters"), TenderSearchCriteria.class);
                TenderQueries.NewTenders found = tenders.addedBetween(criteria, since, to, RECENT_PUBLICATION_DAYS, 5);
                if (found.count() > 0) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("count", found.count());
                    payload.put("name", a.get("name"));
                    payload.put("tenderIds", found.sample().stream().map(UUID::toString).toList());
                    created += jdbc.update("""
                            insert into notifications (id, user_id, organization_id, type, saved_search_id, payload,
                                in_app, email_frequency, dedup_key)
                            values (gen_random_uuid(), ?, ?, 'SAVED_SEARCH', ?, ?::jsonb, true, ?, ?)
                            on conflict (user_id, dedup_key) do nothing
                            """, a.get("user_id"), a.get("organization_id"), id, json.writeValueAsString(payload),
                            a.get("frequency"), "SEARCH:" + id + ":" + to.getEpochSecond());
                }
                jdbc.update("update saved_searches set alert_checked_at = ? where id = ?", ts(to), id);
            } catch (RuntimeException e) {
                // One broken saved search must not stop the others.
                log.warn("Saved-search alert {} failed: {}", id, e.toString());
            }
        }
        return created;
    }

    private static String recipients(String orgColumn, NotificationType t) {
        return RECIPIENTS.formatted(orgColumn, t.name());
    }

    private static String delivery(NotificationType t) {
        return DELIVERY.formatted(t.defaultInApp(), t.defaultEmail()).strip();
    }

    private static String wanted(NotificationType t) {
        return WANTED.formatted(t.defaultInApp(), t.defaultEmail()).strip();
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
