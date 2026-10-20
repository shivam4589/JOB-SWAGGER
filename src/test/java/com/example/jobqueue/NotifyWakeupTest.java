package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.worker.JobNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The poll interval is set to 30 seconds. If these jobs finish within a few seconds, the workers must have been
 * woken by LISTEN/NOTIFY (or by the "earliest due time" calculation for delayed jobs), not by polling.
 */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.worker.enabled=true",
        "jobqueue.worker.concurrency=2",
        "jobqueue.worker.poll-interval-ms=30000",
        "jobqueue.worker.listen-safety-poll-ms=30000"
})
class NotifyWakeupTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JobNotifier notifier;

    @Autowired
    JobRepository repository;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void waitForTheListener() {
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(notifier::isListening);
    }

    private boolean succeeded(UUID id) {
        return repository.findById(id).map(job -> job.status() == JobStatus.SUCCEEDED).orElse(false);
    }

    @Test
    void aNewJobIsPickedUpImmediately() {
        UUID id = repository.insert(UUID.randomUUID(), "NOOP", mapper.createObjectNode(), 5, 0, 5).id();

        Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> succeeded(id));

        assertThat(repository.findById(id).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void aDelayedJobStartsWhenItBecomesDue() {
        UUID id = repository.insert(UUID.randomUUID(), "NOOP", mapper.createObjectNode(), 5, 2, 5).id();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> succeeded(id));
    }
}
