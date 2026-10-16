package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobqueue.schedule.CronSupport;
import com.example.jobqueue.schedule.InvalidScheduleException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CronSupportTest {

    @Test
    void fiveFieldCronGetsASecondsField() {
        assertThat(CronSupport.normalize("*/5 * * * *")).isEqualTo("0 */5 * * * *");
        assertThat(CronSupport.normalize("  0   9  * * 1-5 ")).isEqualTo("0 0 9 * * 1-5");
    }

    @Test
    void sixFieldCronAndMacrosPassThrough() {
        assertThat(CronSupport.normalize("30 */5 * * * *")).isEqualTo("30 */5 * * * *");
        assertThat(CronSupport.normalize("@daily")).isEqualTo("@daily");
    }

    @Test
    void nextRunRespectsTheTimeZone() {
        Instant after = Instant.parse("2026-10-16T10:00:00Z");
        assertThat(CronSupport.next("0 0 3 * * *", "UTC", after))
                .isEqualTo(Instant.parse("2026-10-17T03:00:00Z"));
        // 03:00 in India (UTC+5:30) is 21:30 UTC the evening before
        assertThat(CronSupport.next("0 0 3 * * *", "Asia/Kolkata", after))
                .isEqualTo(Instant.parse("2026-10-16T21:30:00Z"));
    }

    @Test
    void nextRunIsStrictlyAfterTheGivenInstant() {
        Instant onTheHour = Instant.parse("2026-10-16T10:00:00Z");
        assertThat(CronSupport.next("0 0 * * * *", "UTC", onTheHour))
                .isEqualTo(Instant.parse("2026-10-16T11:00:00Z"));
    }

    @Test
    void sensibleSchedulesAreAccepted() {
        assertThatCode(() -> CronSupport.validate("0 */5 * * * *", "UTC")).doesNotThrowAnyException();
        assertThatCode(() -> CronSupport.validate("0 0 9 * * 1-5", "Asia/Kolkata")).doesNotThrowAnyException();
        assertThatCode(() -> CronSupport.validate("@hourly", "UTC")).doesNotThrowAnyException();
    }

    @Test
    void nonsenseIsRejectedWithAReadableMessage() {
        assertThatThrownBy(() -> CronSupport.validate("not a cron", "UTC"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("Invalid cron expression");
        assertThatThrownBy(() -> CronSupport.validate("0 0 3 * * *", "Mars/Phobos"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("time zone");
    }

    @Test
    void schedulesFiringEverySecondAreRejected() {
        assertThatThrownBy(() -> CronSupport.validate("* * * * * *", "UTC"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("more often than");
    }
}
