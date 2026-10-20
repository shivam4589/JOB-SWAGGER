package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.api.JobService;
import com.example.jobqueue.api.SubmitJobRequest;
import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.retention.RetentionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Old finished jobs are deleted in batches; waiting, running and recent jobs are never touched. */
@Testcontainers
@SpringBootTest(properties = {
        "jobqueue.retention.succeeded-days=7",
        "jobqueue.retention.failed-days=30",
        "jobqueue.retention.batch-size=2"
})
class RetentionServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    RetentionService retention;

    @Autowired
    JobRepository jobs;

    @Autowired
    JobService jobService;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    /** Creates a job, then sets its status and pretends its last change was ageDays ago. */
    private UUID job(String status, int ageDays) {
        UUID id = jobs.insert(UUID.randomUUID(), "T", mapper.createObjectNode(), 5, 0, 5).id();
        backdate(id, status, ageDays);
        return id;
    }

    private void backdate(UUID id, String status, int ageDays) {
        jdbc.sql("UPDATE jobs SET status = :status, updated_at = now() - (:days * INTERVAL '1 day') WHERE id = :id")
                .param("status", status)
                .param("days", ageDays)
                .param("id", id)
                .update();
    }

    private Set<UUID> remaining() {
        List<UUID> ids = jdbc.sql("SELECT id FROM jobs").query(UUID.class).list();
        return new HashSet<>(ids);
    }

    @Test
    void oldFinishedJobsAreDeletedAndEverythingElseIsKept() {
        job("SUCCEEDED", 10);                               // older than 7 days -> deleted
        UUID recentSucceeded = job("SUCCEEDED", 1);         // recent -> kept
        UUID deadRecent = job("DEAD", 10);                  // dead jobs are kept 30 days -> kept
        job("DEAD", 40);                                    // older than 30 days -> deleted
        job("CANCELLED", 31);                               // older than 30 days -> deleted
        UUID pendingAncient = job("PENDING", 365);          // waiting jobs are never deleted
        UUID runningAncient = job("RUNNING", 365);          // running jobs are never deleted

        // batch size is 2, so this takes two batches (2 + 1)
        assertThat(retention.purgeOnce()).isEqualTo(3);

        assertThat(remaining()).containsExactlyInAnyOrder(recentSucceeded, deadRecent, pendingAncient, runningAncient);
    }

    @Test
    void attemptHistoryGoesWithTheJob() {
        UUID old = job("SUCCEEDED", 10);
        jobs.startAttempt(old, "worker-1");

        retention.purgeOnce();

        long attempts = jdbc.sql("SELECT count(*) FROM job_attempts WHERE job_id = :id")
                .param("id", old).query(Long.class).single();
        assertThat(attempts).isZero();
    }

    @Test
    void nothingIsDeletedWhenNothingIsOld() {
        job("SUCCEEDED", 1);
        job("DEAD", 5);

        assertThat(retention.purgeOnce()).isZero();
        assertThat(remaining()).hasSize(2);
    }

    @Test
    void anIdempotencyKeyCanBeUsedAgainOnceItsJobWasPurged() {
        SubmitJobRequest request = new SubmitJobRequest("T", mapper.createObjectNode(), null, null, null);
        JobService.SubmitResult first = jobService.submit(request, "purge-key");
        assertThat(first.created()).isTrue();
        assertThat(jobService.submit(request, "purge-key").created()).isFalse(); // still remembered

        backdate(first.job().id(), "SUCCEEDED", 10);
        retention.purgeOnce();

        JobService.SubmitResult again = jobService.submit(request, "purge-key");
        assertThat(again.created()).isTrue();
        assertThat(again.job().id()).isNotEqualTo(first.job().id());
    }
}
