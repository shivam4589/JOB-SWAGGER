package com.example.jobqueue.repo;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public JobRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ API side

    public Job insert(UUID id, String type, JsonNode payload,
                      int priority, int delaySeconds, int maxAttempts) {
        return jdbc.sql("""
                INSERT INTO jobs (id, type, payload, status, priority, run_at, max_attempts)
                VALUES (:id, :type, CAST(:payload AS jsonb), 'PENDING', :priority,
                        now() + (:delay * INTERVAL '1 second'), :maxAttempts)
                RETURNING *
                """)
                .param("id", id)
                .param("type", type)
                .param("payload", payload.toString())
                .param("priority", priority)
                .param("delay", delaySeconds)
                .param("maxAttempts", maxAttempts)
                .query(this::map)
                .single();
    }

    public Optional<Job> findById(UUID id) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<Job> list(JobStatus status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM jobs ORDER BY created_at DESC LIMIT :limit")
                    .param("limit", limit)
                    .query(this::map)
                    .list();
        }
        return jdbc.sql("""
                SELECT * FROM jobs WHERE status = :status
                ORDER BY created_at DESC LIMIT :limit
                """)
                .param("status", status.name())
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public List<JobAttempt> attemptsFor(UUID jobId) {
        return jdbc.sql("SELECT * FROM job_attempts WHERE job_id = :id ORDER BY id")
                .param("id", jobId)
                .query(this::mapAttempt)
                .list();
    }

    // --------------------------------------------------------------- worker side

    /**
     * Atomically claims the highest-priority due job for this worker.
     * FOR UPDATE SKIP LOCKED lets many workers poll at once: a row locked by another
     * worker is skipped instead of waited on, so no job is ever handed out twice.
     * Retries re-enter here too: a job waiting out its backoff has run_at in the future.
     */
    public Optional<Job> claimNext(String workerId) {
        return jdbc.sql("""
                UPDATE jobs
                SET status = 'RUNNING', locked_by = :worker, locked_at = now(),
                    attempts = attempts + 1, updated_at = now()
                WHERE id = (
                    SELECT id FROM jobs
                    WHERE status = 'PENDING' AND run_at <= now()
                    ORDER BY priority DESC, run_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING *
                """)
                .param("worker", workerId)
                .query(this::map)
                .optional();
    }

    public long startAttempt(UUID jobId, String workerId) {
        return jdbc.sql("""
                INSERT INTO job_attempts (job_id, worker_id) VALUES (:jobId, :worker)
                RETURNING id
                """)
                .param("jobId", jobId)
                .param("worker", workerId)
                .query(Long.class)
                .single();
    }

    public void finishAttempt(long attemptId, boolean success, String error) {
        jdbc.sql("""
                UPDATE job_attempts SET finished_at = now(), success = :success, error = :error
                WHERE id = :id
                """)
                .param("success", success)
                .param("error", error)
                .param("id", attemptId)
                .update();
    }

    /**
     * The three transitions below only apply if this worker still holds the lock
     * (status RUNNING and locked_by = worker). If the reaper reclaimed the job in the
     * meantime, a late result from a slow worker is ignored and 0 rows are updated.
     */
    public int markSucceeded(UUID id, String workerId) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'SUCCEEDED', last_error = NULL,
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .update();
    }

    /** Failure with attempts left: back to PENDING, but not due until the backoff has passed. */
    public int scheduleRetry(UUID id, String workerId, String error, double delaySeconds) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'PENDING', last_error = :error,
                    run_at = now() + (:delay * INTERVAL '1 second'),
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .param("error", error)
                .param("delay", delaySeconds)
                .update();
    }

    /** Failure with no attempts left: dead-letter state. The job stays in the table for inspection. */
    public int markDead(UUID id, String workerId, String error) {
        return jdbc.sql("""
                UPDATE jobs SET status = 'DEAD', last_error = :error,
                    locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :worker
                """)
                .param("id", id)
                .param("worker", workerId)
                .param("error", error)
                .update();
    }

    /**
     * Recovers jobs whose worker vanished (crash, kill, network partition): RUNNING jobs whose
     * lock is older than the timeout go back to PENDING, or to DEAD if they used all attempts.
     * One atomic statement; SKIP LOCKED makes it safe for several reapers to run at once.
     * Returns how many jobs were reclaimed.
     */
    public int reapStuckJobs(double lockTimeoutSeconds) {
        return jdbc.sql("""
                WITH stuck AS (
                    SELECT id FROM jobs
                    WHERE status = 'RUNNING'
                      AND locked_at < now() - (:timeout * INTERVAL '1 second')
                    FOR UPDATE SKIP LOCKED
                ), closed AS (
                    UPDATE job_attempts
                    SET finished_at = now(), success = FALSE, error = 'Worker lost: lock expired'
                    WHERE finished_at IS NULL AND job_id IN (SELECT id FROM stuck)
                )
                UPDATE jobs
                SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    last_error = 'Worker lost: lock expired (locked by '
                                 || COALESCE(locked_by, 'unknown') || ')',
                    run_at = now(), locked_by = NULL, locked_at = NULL, updated_at = now()
                WHERE id IN (SELECT id FROM stuck)
                """)
                .param("timeout", lockTimeoutSeconds)
                .update();
    }

    // ------------------------------------------------------------------- mapping

    private Job map(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new Job(
                    rs.getObject("id", UUID.class),
                    rs.getString("type"),
                    mapper.readTree(rs.getString("payload")),
                    JobStatus.valueOf(rs.getString("status")),
                    rs.getInt("priority"),
                    rs.getTimestamp("run_at").toInstant(),
                    rs.getInt("attempts"),
                    rs.getInt("max_attempts"),
                    rs.getString("last_error"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid JSON payload in database", e);
        }
    }

    private JobAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new JobAttempt(
                rs.getLong("id"),
                rs.getObject("job_id", UUID.class),
                rs.getString("worker_id"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(),
                rs.getObject("success", Boolean.class),
                rs.getString("error"));
    }
}
