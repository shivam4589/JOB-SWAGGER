package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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

/** Retry scheduling, dead-lettering and crash recovery, tested directly against Postgres (no worker threads). */
@Testcontainers
@SpringBootTest
class RetryAndReaperRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JobRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM jobs").update(); // job_attempts rows go too (ON DELETE CASCADE)
    }

    private Job newJob(int maxAttempts) {
        return repository.insert(UUID.randomUUID(), "NOOP", mapper.createObjectNode(), 5, 0, maxAttempts);
    }

    private void makeLockStale(UUID id) {
        jdbc.sql("UPDATE jobs SET locked_at = now() - INTERVAL '10 minutes' WHERE id = :id")
                .param("id", id).update();
    }

    @Test
    void retryIsNotClaimableUntilBackoffHasPassed() {
        Job job = newJob(5);
        Job claimed = repository.claimNext("w1").orElseThrow();
        assertThat(claimed.attempts()).isEqualTo(1);

        assertThat(repository.scheduleRetry(job.id(), "w1", "temporary error", 3600)).isEqualTo(1);

        Job after = repository.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.PENDING);
        assertThat(after.lastError()).isEqualTo("temporary error");
        assertThat(after.runAt()).isAfter(Instant.now().plusSeconds(3000));
        assertThat(repository.claimNext("w2")).isEmpty();
    }

    @Test
    void markDeadParksTheJobWithItsError() {
        Job job = newJob(1);
        repository.claimNext("w1").orElseThrow();

        assertThat(repository.markDead(job.id(), "w1", "gave up")).isEqualTo(1);

        Job after = repository.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.DEAD);
        assertThat(after.lastError()).isEqualTo("gave up");
        assertThat(repository.claimNext("w2")).isEmpty();
    }

    @Test
    void reaperRecoversJobAbandonedByCrashedWorker() {
        Job job = newJob(3);
        repository.claimNext("ghost").orElseThrow();
        repository.startAttempt(job.id(), "ghost");
        makeLockStale(job.id());

        assertThat(repository.reapStuckJobs(60)).isEqualTo(1);

        Job after = repository.findById(job.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(JobStatus.PENDING);
        assertThat(after.attempts()).isEqualTo(1);
        assertThat(after.lastError()).contains("Worker lost");

        List<JobAttempt> attempts = repository.attemptsFor(job.id());
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).success()).isFalse();
        assertThat(attempts.get(0).finishedAt()).isNotNull();

        // another worker can now pick it up, as attempt number 2
        Job retried = repository.claimNext("w2").orElseThrow();
        assertThat(retried.id()).isEqualTo(job.id());
        assertThat(retried.attempts()).isEqualTo(2);
    }

    @Test
    void reaperKillsAbandonedJobThatUsedAllAttempts() {
        Job job = newJob(1);
        repository.claimNext("ghost").orElseThrow();
        makeLockStale(job.id());

        assertThat(repository.reapStuckJobs(60)).isEqualTo(1);

        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.DEAD);
    }

    @Test
    void reaperLeavesFreshJobsAlone() {
        Job job = newJob(3);
        repository.claimNext("busy-worker").orElseThrow();

        assertThat(repository.reapStuckJobs(60)).isZero();

        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void heartbeatKeepsALongRunningJobFromBeingReclaimed() {
        Job job = newJob(3);
        repository.claimNext("w1").orElseThrow();
        makeLockStale(job.id());

        assertThat(repository.touchLock(job.id(), "w1")).isEqualTo(1); // the worker proves it is still alive

        assertThat(repository.reapStuckJobs(60)).isZero();
        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void heartbeatFromAWorkerThatLostTheJobIsIgnored() {
        Job job = newJob(3);
        repository.claimNext("ghost").orElseThrow();
        makeLockStale(job.id());
        repository.reapStuckJobs(60); // reclaimed

        assertThat(repository.touchLock(job.id(), "ghost")).isZero();
    }

    @Test
    void heartbeatReportsACancellationRequest() {
        Job job = newJob(3);
        repository.claimNext("w1").orElseThrow();

        assertThat(repository.heartbeat(job.id(), "w1")).contains(false);
        assertThat(repository.requestCancelIfRunning(job.id())).isPresent();
        assertThat(repository.heartbeat(job.id(), "w1")).contains(true);
        assertThat(repository.heartbeat(job.id(), "someone-else")).isEmpty(); // not the owner
    }

    @Test
    void reaperCancelsAnAbandonedJobWhoseCancellationWasRequested() {
        Job job = newJob(3);
        repository.claimNext("ghost").orElseThrow();
        repository.requestCancelIfRunning(job.id());
        makeLockStale(job.id());

        assertThat(repository.reapStuckJobs(60)).isEqualTo(1);

        // the worker is gone, but the user wanted the job stopped: it must not come back to the queue
        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void lateResultFromReclaimedWorkerIsIgnored() {
        Job job = newJob(3);
        repository.claimNext("ghost").orElseThrow();
        makeLockStale(job.id());
        repository.reapStuckJobs(60);

        // the "dead" worker wakes up and reports success: it no longer owns the job
        assertThat(repository.markSucceeded(job.id(), "ghost")).isZero();
        assertThat(repository.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.PENDING);
    }
}
