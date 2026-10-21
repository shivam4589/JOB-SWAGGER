package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.api.JobService;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A job that would run for a minute is stopped within a heartbeat after cancellation is requested. */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=2",
        "jobqueue.worker.poll-interval-ms=100",
        "jobqueue.worker.heartbeat-interval-ms=500"
})
class CancelRunningJobEndToEndTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JobRepository repository;

    @Autowired
    JobService service;

    @Autowired
    ObjectMapper mapper;

    private boolean hasStatus(UUID id, JobStatus status) {
        return repository.findById(id).map(job -> job.status() == status).orElse(false);
    }

    @Test
    void aRunningJobIsStoppedOnRequestAndTheWorkerKeepsWorking() {
        ObjectNode payload = mapper.createObjectNode().put("seconds", 60); // the built-in SLOW job type
        UUID id = repository.insert(UUID.randomUUID(), "SLOW", payload, 5, 0, 5).id();
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> hasStatus(id, JobStatus.RUNNING));

        JobService.CancelResult result = service.cancel(id);
        assertThat(result.immediate()).isFalse();        // not cancelled yet: only requested
        assertThat(result.job().cancelRequested()).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> hasStatus(id, JobStatus.CANCELLED));

        List<JobAttempt> attempts = repository.attemptsFor(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).success()).isFalse();
        assertThat(attempts.get(0).error()).contains("Cancelled");

        // the thread that was interrupted must still be alive: ordinary jobs keep being processed
        List<UUID> followUps = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            followUps.add(repository.insert(UUID.randomUUID(), "NOOP", mapper.createObjectNode(), 5, 0, 5).id());
        }
        Awaitility.await().atMost(Duration.ofSeconds(15)).until(
                () -> followUps.stream().allMatch(followUp -> hasStatus(followUp, JobStatus.SUCCEEDED)));
    }
}
