package com.example.jobqueue.schedule;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;

/** Cron parsing and "when does it fire next" on top of Spring's CronExpression. */
public final class CronSupport {

    /** Schedules may not fire more often than this, so a typo cannot flood the queue. */
    public static final Duration MIN_GAP = Duration.ofSeconds(10);

    private CronSupport() {
    }

    /** Accepts the usual 5-field cron by adding a "0" seconds field; macros and 6-field cron pass through. */
    public static String normalize(String expression) {
        String trimmed = expression.trim().replaceAll("\\s+", " ");
        if (trimmed.startsWith("@")) {
            return trimmed;
        }
        return trimmed.split(" ").length == 5 ? "0 " + trimmed : trimmed;
    }

    /** The first fire time strictly after the given instant, or null if the expression never fires again. */
    public static Instant next(String cron, String timezone, Instant after) {
        ZonedDateTime next = CronExpression.parse(cron).next(after.atZone(ZoneId.of(timezone)));
        return next == null ? null : next.toInstant();
    }

    /** Throws InvalidScheduleException with a readable message if the schedule cannot be used. */
    public static void validate(String normalizedCron, String timezone) {
        CronExpression expression;
        try {
            expression = CronExpression.parse(normalizedCron);
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleException("Invalid cron expression: " + e.getMessage());
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new InvalidScheduleException("Unknown time zone: " + timezone);
        }
        ZonedDateTime first = expression.next(ZonedDateTime.now(zone));
        if (first == null) {
            throw new InvalidScheduleException("This cron expression never fires");
        }
        ZonedDateTime second = expression.next(first);
        if (second != null && Duration.between(first, second).compareTo(MIN_GAP) < 0) {
            throw new InvalidScheduleException(
                    "Schedules may not fire more often than every " + MIN_GAP.toSeconds() + " seconds");
        }
    }
}
