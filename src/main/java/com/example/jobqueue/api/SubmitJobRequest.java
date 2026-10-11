package com.example.jobqueue.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitJobRequest(
        @NotBlank @Size(max = 100) String type,
        @NotNull JsonNode payload,
        @Min(1) @Max(10) Integer priority,
        @Min(0) Integer delaySeconds,
        @Min(1) @Max(20) Integer maxAttempts) {
}
