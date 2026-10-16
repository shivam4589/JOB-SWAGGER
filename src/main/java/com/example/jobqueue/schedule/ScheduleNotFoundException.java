package com.example.jobqueue.schedule;

import java.util.UUID;

public class ScheduleNotFoundException extends RuntimeException {
    public ScheduleNotFoundException(UUID id) {
        super("Schedule not found: " + id);
    }
}
