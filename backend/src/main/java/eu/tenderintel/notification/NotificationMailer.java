package eu.tenderintel.notification;

import eu.tenderintel.billing.Plan;
import eu.tenderintel.config.AppProperties;
import eu.tenderintel.mail.EmailOutbox;
import eu.tenderintel.mail.EmailTemplate;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends notification e-mails: one message per person and organization, combining everything that is due. Instant
 * items go out at the next scan (plans without instant e-mails get them in the daily digest), daily digests at the
 * configured local hour of the person's time zone, weekly digests on Mondays at that hour.
 */
@Component
class NotificationMailer {

    static final int MAX_ITEMS_PER_SECTION = 15;
    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmailOutbox outbox;
    private final MessageSource messages;
    private final UnsubscribeTokens tokens;
    private final JsonMapper json;
    private final AppProperties props;

    NotificationMailer(JdbcTemplate jdbc, TransactionTemplate tx, EmailOutbox outbox, MessageSource messages,
            UnsubscribeTokens tokens, JsonMapper json, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.messages = messages;
        this.tokens = tokens;
        this.json = json;
        this.props = props;
    }

    /** Returns the number of e-mails queued. */
    int sendDue(Instant now) {
        List<Map<String, Object>> groups = jdbc.queryForList("""
                select n.user_id, n.organization_id, u.email::text as email, u.full_name, u.locale, u.timezone,
                       u.email_verified_at is not null as verified, o.name as org_name, o.plan_code
                from notifications n
                join users u on u.id = n.user_id and u.status = 'ACTIVE'
                join organization_members om on om.organization_id = n.organization_id and om.user_id = n.user_id
                join organizations o on o.id = n.organization_id
                where n.emailed_at is null and n.email_frequency <> 'OFF'
                group by 1, 2, 3, 4, 5, 6, 7, 8, 9
                """);
        int sent = 0;
        for (Map<String, Object> g : groups) {
            try {
                Boolean queued = tx.execute(status -> sendGroup(g, now));
                if (Boolean.TRUE.equals(queued)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.error("Notification e-mail for user {} failed", g.get("user_id"), e);
            }
        }
        return sent;
    }

    private boolean sendGroup(Map<String, Object> g, Instant now) {
        UUID userId = (UUID) g.get("user_id");
        UUID orgId = (UUID) g.get("organization_id");
        if (!Boolean.TRUE.equals(g.get("verified"))) {
            // Never e-mail an unconfirmed address; the notifications stay visible in the app.
            jdbc.update("update notifications set emailed_at = ? where user_id = ? and organization_id = ? "
                    + "and emailed_at is null", Timestamp.from(now), userId, orgId);
            return false;
        }
        Plan plan = Plan.of((String) g.get("plan_code"));
        ZoneId zone = zone((String) g.get("timezone"));
        Map<String, Instant> lastSent = new HashMap<>();
        jdbc.query("select frequency, last_sent_at from notification_digests where user_id = ? "
                + "and organization_id = ?", rs -> {
                    lastSent.put(rs.getString(1), rs.getTimestamp(2).toInstant());
                }, userId, orgId);
        Set<String> due = new HashSet<>();
        if (plan.instantEmails()) {
            due.add("INSTANT");
        }
        if (isDue(lastSent.get("DAILY"), dailySlot(now, zone, props.notifications().digestHour()))) {
            due.add("DAILY");
        }
        if (isDue(lastSent.get("WEEKLY"), weeklySlot(now, zone, props.notifications().digestHour()))) {
            due.add("WEEKLY");
        }
        // Without instant e-mails in the plan, "instant" items travel with the daily digest.
        String instantAs = plan.instantEmails() ? "INSTANT" : "DAILY";
        List<Item> items = jdbc.query("""
                select n.id, n.type, n.email_frequency, n.payload::text, n.tender_id, t.title, t.country_code,
                       t.submission_deadline, n.saved_search_id, n.created_at
                from notifications n left join tenders t on t.id = n.tender_id
                where n.user_id = ? and n.organization_id = ? and n.emailed_at is null and n.email_frequency <> 'OFF'
                order by n.created_at
                for update of n skip locked
                """, (rs, i) -> new Item(rs.getObject(1, UUID.class), NotificationType.valueOf(rs.getString(2)),
                "INSTANT".equals(rs.getString(3)) ? instantAs : rs.getString(3), json.readTree(rs.getString(4)),
                rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(), rs.getObject(9, UUID.class)),
                userId, orgId);
        List<Item> dueItems = items.stream().filter(i -> due.contains(i.frequency())).toList();
        if (dueItems.isEmpty()) {
            return false;
        }
        String locale = "pl".equals(g.get("locale")) ? "pl" : "en";
        String token = tokens.create(userId, orgId);
        String unsubscribePage = props.publicUrl() + "/unsubscribe?token=" + token;
        String oneClick = props.publicUrl() + "/api/v1/notifications/unsubscribe?token=" + token;
        Rendered mail = render(dueItems, (String) g.get("full_name"), (String) g.get("org_name"), locale, zone,
                unsubscribePage);
        outbox.enqueueComposed((String) g.get("email"), EmailTemplate.NOTIFICATION_DIGEST, locale, mail.subject(),
                mail.body(), "mail.footer.notifications", oneClick);
        jdbc.batchUpdate("update notifications set emailed_at = ? where id = ?",
                dueItems.stream().map(i -> new Object[] {Timestamp.from(now), i.id()}).toList());
        for (String f : dueItems.stream().map(Item::frequency).distinct().toList()) {
            jdbc.update("""
                    insert into notification_digests (user_id, organization_id, frequency, last_sent_at)
                    values (?, ?, ?, ?)
                    on conflict (user_id, organization_id, frequency) do update set last_sent_at = excluded.last_sent_at
                    """, userId, orgId, f, Timestamp.from(now));
        }
        return true;
    }

    static boolean isDue(Instant lastSent, Instant slot) {
        return lastSent == null || lastSent.isBefore(slot);
    }

    /** The most recent daily send time (today's hour, or yesterday's if it is earlier than that). */
    static Instant dailySlot(Instant now, ZoneId zone, int hour) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime slot = local.toLocalDate().atTime(hour, 0).atZone(zone);
        return (slot.isAfter(local) ? slot.minusDays(1) : slot).toInstant();
    }

    /** The most recent Monday at the send hour. */
    static Instant weeklySlot(Instant now, ZoneId zone, int hour) {
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime slot = local.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atTime(hour, 0).atZone(zone);
        return (slot.isAfter(local) ? slot.minusWeeks(1) : slot).toInstant();
    }

    // --- rendering ----------------------------------------------------------------------------------------------

    Rendered render(List<Item> items, String fullName, String orgName, String locale, ZoneId zone,
            String unsubscribeUrl) {
        Locale loc = Locale.forLanguageTag(locale);
        DateTimeFormatter dates = DateTimeFormatter.ofPattern("pl".equals(locale) ? "dd.MM.yyyy HH:mm"
                : "d MMM yyyy, HH:mm", loc).withZone(zone);
        String base = props.publicUrl();
        Map<NotificationType, List<Item>> byType = new EnumMap<>(NotificationType.class);
        for (Item i : items) {
            byType.computeIfAbsent(i.type(), k -> new ArrayList<>()).add(i);
        }
        StringBuilder b = new StringBuilder();
        String firstName = fullName == null ? "" : fullName.trim().split("\\s+")[0];
        b.append(msg("notif.mail.greeting", loc, firstName)).append("\n\n");
        b.append(msg("notif.mail.intro", loc, orgName)).append('\n');
        for (Map.Entry<NotificationType, List<Item>> e : byType.entrySet()) {
            List<Item> list = new ArrayList<>(e.getValue());
            if (e.getKey() == NotificationType.NEW_MATCH) {
                list.sort((x, y) -> Integer.compare(y.payload().path("score").asInt(),
                        x.payload().path("score").asInt()));
            } else if (e.getKey() == NotificationType.DEADLINE_APPROACHING) {
                list.sort((x, y) -> x.deadline() == null ? 1 : y.deadline() == null ? -1
                        : x.deadline().compareTo(y.deadline()));
            }
            b.append('\n').append(msg("notif.mail.section." + e.getKey().name(), loc)).append('\n');
            for (Item i : list.subList(0, Math.min(list.size(), MAX_ITEMS_PER_SECTION))) {
                b.append("\n• ").append(headline(i, loc, dates)).append('\n');
                String detail = detail(i, loc, dates);
                if (detail != null) {
                    b.append("  ").append(detail).append('\n');
                }
                b.append("  ").append(link(i, base)).append('\n');
            }
            if (list.size() > MAX_ITEMS_PER_SECTION) {
                b.append('\n').append(msg("notif.mail.more", loc, list.size() - MAX_ITEMS_PER_SECTION,
                        e.getKey() == NotificationType.NEW_MATCH ? base + "/matches" : base + "/notifications"))
                        .append('\n');
            }
        }
        b.append('\n').append(msg("notif.mail.all", loc, base + "/notifications")).append('\n');
        b.append(msg("notif.mail.settings", loc, base + "/notifications?tab=settings")).append('\n');
        b.append(msg("notif.mail.unsubscribe", loc, unsubscribeUrl));
        String subject = items.size() == 1 ? headline(items.get(0), loc, dates)
                : msg("notif.mail.subject", loc, orgName, items.size());
        if (subject.length() > 150) {
            subject = subject.substring(0, 149) + "…";
        }
        return new Rendered(subject, b.toString());
    }

    private String headline(Item i, Locale loc, DateTimeFormatter dates) {
        String title = i.title() == null ? "" : i.title();
        return switch (i.type()) {
            case NEW_MATCH -> msg("notif.mail.match", loc, i.payload().path("score").asInt(), title);
            case WATCHED_CHANGED -> msg("notif.mail.changed", loc, title);
            case DEADLINE_APPROACHING -> msg("notif.mail.deadlineSoon", loc, title);
            case SAVED_SEARCH -> msg("notif.mail.search", loc, i.payload().path("name").asString(""),
                    i.payload().path("count").asLong());
        };
    }

    private String detail(Item i, Locale loc, DateTimeFormatter dates) {
        return switch (i.type()) {
            case NEW_MATCH, DEADLINE_APPROACHING -> i.deadline() == null ? null
                    : msg("notif.mail.deadline", loc, dates.format(i.deadline()));
            case WATCHED_CHANGED -> changes(i.payload(), loc, dates);
            case SAVED_SEARCH -> null;
        };
    }

    String changes(JsonNode payload, Locale loc, DateTimeFormatter dates) {
        List<String> parts = new ArrayList<>();
        String kind = payload.path("changeKind").asString("");
        if ("CANCELLATION".equals(kind)) {
            parts.add(msg("notif.mail.change.cancelled", loc));
        } else if ("AWARD".equals(kind)) {
            parts.add(msg("notif.mail.change.awarded", loc));
        }
        for (JsonNode c : payload.path("changes")) {
            String field = c.path("field").asString("");
            switch (field) {
                case "submissionDeadline" -> parts.add(msg("notif.mail.change.submissionDeadline", loc,
                        date(c.path("old"), dates, loc), date(c.path("new"), dates, loc)));
                case "status" -> {
                    if (!("CANCELLATION".equals(kind) || "AWARD".equals(kind))) {
                        parts.add(msg("notif.mail.change.status", loc, status(c.path("old"), loc),
                                status(c.path("new"), loc)));
                    }
                }
                case "estimatedValue" -> parts.add(msg("notif.mail.change.estimatedValue", loc,
                        number(c.path("old"), loc), number(c.path("new"), loc)));
                case "documentsUrl" -> parts.add(msg("notif.mail.change.documentsUrl", loc));
                default -> {
                    // Other fields are not reported.
                }
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private String date(JsonNode v, DateTimeFormatter dates, Locale loc) {
        if (v == null || v.isNull() || v.isMissingNode() || v.asString("").isEmpty()) {
            return msg("notif.mail.none", loc);
        }
        try {
            return dates.format(Instant.parse(v.asString()));
        } catch (RuntimeException e) {
            return v.asString();
        }
    }

    private String status(JsonNode v, Locale loc) {
        String s = v == null || v.isNull() ? null : v.asString(null);
        return s == null ? msg("notif.mail.none", loc) : msg("notif.mail.status." + s, loc);
    }

    private String number(JsonNode v, Locale loc) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return msg("notif.mail.none", loc);
        }
        try {
            return NumberFormat.getNumberInstance(loc).format(new BigDecimal(v.asString()));
        } catch (RuntimeException e) {
            return v.asString();
        }
    }

    private static String link(Item i, String base) {
        if (i.type() == NotificationType.SAVED_SEARCH) {
            return base + "/saved-searches";
        }
        return base + "/tenders/" + i.tenderId();
    }

    private String msg(String key, Locale loc, Object... args) {
        return messages.getMessage(key, args, key, loc);
    }

    private static ZoneId zone(String id) {
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneId.of("Europe/Warsaw");
        }
    }

    record Item(UUID id, NotificationType type, String frequency, JsonNode payload, UUID tenderId, String title,
            String country, Instant deadline, UUID savedSearchId) {
    }

    record Rendered(String subject, String body) {
    }
}
