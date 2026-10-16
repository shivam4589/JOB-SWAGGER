package com.example.jobqueue.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record Schedule(
        UUID id,
        String name,
        String type,
        JsonNode payload,
        int priority,
        int maxAttempts,
        String cron,
        String timezone,
        boolean enabled,
        Instant nextRunAt,
        Instant lastRunAt,
        UUID lastJobId,
        Instant createdAt,
        Instant updatedAt) {
}
