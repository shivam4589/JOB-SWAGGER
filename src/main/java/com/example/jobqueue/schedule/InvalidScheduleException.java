package com.example.jobqueue.schedule;

/** The cron expression or time zone of a schedule is not acceptable (HTTP 400). */
public class InvalidScheduleException extends RuntimeException {
    public InvalidScheduleException(String message) {
        super(message);
    }
}
