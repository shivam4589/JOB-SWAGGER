package com.example.jobqueue.schedule;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * cron: 5 fields ("*&#47;5 * * * *") or Spring's 6 fields with seconds ("0 *&#47;5 * * * *"), or a macro such as @daily.
 * timezone: an IANA zone such as "Asia/Kolkata" (default UTC).
 */
public record CreateScheduleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String type,
        JsonNode payload,
        @NotBlank @Size(max = 120) String cron,
        @Size(max = 50) String timezone,
        @Min(1) @Max(10) Integer priority,
        @Min(1) @Max(20) Integer maxAttempts,
        Boolean enabled) {
}
