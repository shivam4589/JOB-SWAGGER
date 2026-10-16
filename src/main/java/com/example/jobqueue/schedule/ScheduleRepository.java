package com.example.jobqueue.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public ScheduleRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Schedule insert(UUID id, String name, String type, JsonNode payload, int priority,
                           int maxAttempts, String cron, String timezone, boolean enabled,
                           Instant nextRunAt) {
        return jdbc.sql("""
                INSERT INTO schedules (id, name, job_type, payload, priority, max_attempts,
                                       cron, timezone, enabled, next_run_at)
                VALUES (:id, :name, :type, CAST(:payload AS jsonb), :priority, :maxAttempts,
                        :cron, :tz, :enabled, :next)
                RETURNING *
                """)
                .param("id", id)
                .param("name", name)
                .param("type", type)
                .param("payload", payload.toString())
                .param("priority", priority)
                .param("maxAttempts", maxAttempts)
                .param("cron", cron)
                .param("tz", timezone)
                .param("enabled", enabled)
                .param("next", OffsetDateTime.ofInstant(nextRunAt, ZoneOffset.UTC))
                .query(this::map)
                .single();
    }

    public Optional<Schedule> findById(UUID id) {
        return jdbc.sql("SELECT * FROM schedules WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    public List<Schedule> list() {
        return jdbc.sql("SELECT * FROM schedules ORDER BY created_at DESC")
                .query(this::map)
                .list();
    }

    public Optional<Schedule> pause(UUID id) {
        return jdbc.sql("""
                UPDATE schedules SET enabled = FALSE, updated_at = now()
                WHERE id = :id
                RETURNING *
                """)
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** Re-enables a schedule; the next run is recomputed from now so a long pause does not trigger a burst. */
    public Optional<Schedule> resume(UUID id, Instant nextRunAt) {
        return jdbc.sql("""
                UPDATE schedules SET enabled = TRUE, next_run_at = :next, updated_at = now()
                WHERE id = :id
                RETURNING *
                """)
                .param("id", id)
                .param("next", OffsetDateTime.ofInstant(nextRunAt, ZoneOffset.UTC))
                .query(this::map)
                .optional();
    }

    public int delete(UUID id) {
        return jdbc.sql("DELETE FROM schedules WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Locks the enabled schedules that are due. Must run inside a transaction: the row locks last until it
     * ends, and SKIP LOCKED makes a second worker container skip rows another one is already handling,
     * so each due run is fired by exactly one container.
     */
    public List<Schedule> lockDue(int limit) {
        return jdbc.sql("""
                SELECT * FROM schedules
                WHERE enabled AND next_run_at <= now()
                ORDER BY next_run_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public void markFired(UUID id, UUID jobId, Instant nextRunAt) {
        jdbc.sql("""
                UPDATE schedules
                SET last_run_at = now(), last_job_id = :job, next_run_at = :next, updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("job", jobId)
                .param("next", OffsetDateTime.ofInstant(nextRunAt, ZoneOffset.UTC))
                .update();
    }

    /** The cron has no further occurrence: record the last run and switch the schedule off. */
    public void markFiredAndDisable(UUID id, UUID jobId) {
        jdbc.sql("""
                UPDATE schedules
                SET last_run_at = now(), last_job_id = :job, enabled = FALSE, updated_at = now()
                WHERE id = :id
                """)
                .param("id", id)
                .param("job", jobId)
                .update();
    }

    private Schedule map(ResultSet rs, int rowNum) throws SQLException {
        try {
            Timestamp lastRun = rs.getTimestamp("last_run_at");
            return new Schedule(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("job_type"),
                    mapper.readTree(rs.getString("payload")),
                    rs.getInt("priority"),
                    rs.getInt("max_attempts"),
                    rs.getString("cron"),
                    rs.getString("timezone"),
                    rs.getBoolean("enabled"),
                    rs.getTimestamp("next_run_at").toInstant(),
                    lastRun == null ? null : lastRun.toInstant(),
                    rs.getObject("last_job_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid JSON payload in database", e);
        }
    }
}
