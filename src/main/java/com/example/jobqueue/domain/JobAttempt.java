package com.example.jobqueue.domain;

import java.time.Instant;
import java.util.UUID;

/** One execution try of a job. finishedAt/success are null while the attempt is still running. */
public record JobAttempt(
        long id,
        UUID jobId,
        String workerId,
        Instant startedAt,
        Instant finishedAt,
        Boolean success,
        String error) {
}
