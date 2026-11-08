package eu.tenderintel.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationMailerTest {

    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    @Test
    void dailyDigestSlotIsTheLastSevenOClockInTheUsersZone() {
        // 05:30 UTC = 06:30 in Warsaw (winter): before 7 → yesterday's slot.
        assertThat(NotificationMailer.dailySlot(Instant.parse("2026-11-09T05:30:00Z"), WARSAW, 7))
                .isEqualTo(Instant.parse("2026-11-08T06:00:00Z"));
        // 06:00 UTC = 07:00 → today's slot.
        assertThat(NotificationMailer.dailySlot(Instant.parse("2026-11-09T06:00:00Z"), WARSAW, 7))
                .isEqualTo(Instant.parse("2026-11-09T06:00:00Z"));
        // Summer time: 07:00 in Warsaw = 05:00 UTC.
        assertThat(NotificationMailer.dailySlot(Instant.parse("2026-07-01T12:00:00Z"), WARSAW, 7))
                .isEqualTo(Instant.parse("2026-07-01T05:00:00Z"));
    }

    @Test
    void weeklyDigestSlotIsMondayMorning() {
        // Sunday 2026-11-08 → previous Monday 2026-11-02.
        assertThat(NotificationMailer.weeklySlot(Instant.parse("2026-11-08T20:00:00Z"), WARSAW, 7))
                .isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
        // Monday before 7 → the Monday a week earlier.
        assertThat(NotificationMailer.weeklySlot(Instant.parse("2026-11-09T05:00:00Z"), WARSAW, 7))
                .isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
        assertThat(NotificationMailer.weeklySlot(Instant.parse("2026-11-09T06:30:00Z"), WARSAW, 7))
                .isEqualTo(Instant.parse("2026-11-09T06:00:00Z"));
    }

    @Test
    void aDigestIsDueOncePerSlot() {
        Instant slot = Instant.parse("2026-11-09T06:00:00Z");
        assertThat(NotificationMailer.isDue(null, slot)).isTrue();
        assertThat(NotificationMailer.isDue(Instant.parse("2026-11-08T06:01:00Z"), slot)).isTrue();
        assertThat(NotificationMailer.isDue(Instant.parse("2026-11-09T06:01:00Z"), slot)).isFalse();
    }
}
