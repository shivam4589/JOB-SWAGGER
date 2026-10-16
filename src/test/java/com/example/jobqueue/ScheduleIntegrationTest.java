package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.repo.JobRepository;
import com.example.jobqueue.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Recurring jobs through the HTTP API and the scheduler tick, against a real Postgres. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class ScheduleIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ScheduleService service;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
        jdbc.sql("DELETE FROM schedules").update();
    }

    private JsonNode create(String name, String cron) throws Exception {
        String body = mapper.writeValueAsString(Map.of(
                "name", name, "type", "NOOP", "payload", Map.of("k", 1), "cron", cron));
        String json = mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private void makeDue(String scheduleId) {
        jdbc.sql("UPDATE schedules SET next_run_at = now() - INTERVAL '1 hour' WHERE id = :id")
                .param("id", UUID.fromString(scheduleId)).update();
    }

    private long jobsOf(String scheduleId) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE schedule_id = :id")
                .param("id", UUID.fromString(scheduleId)).query(Long.class).single();
    }

    @Test
    void createNormalizesCronAndPlansTheNextRun() throws Exception {
        JsonNode schedule = create("every-five", "*/5 * * * *");

        assertThat(schedule.get("cron").asText()).isEqualTo("0 */5 * * * *");
        assertThat(schedule.get("enabled").asBoolean()).isTrue();
        assertThat(Instant.parse(schedule.get("nextRunAt").asText())).isAfter(Instant.now());
    }

    @Test
    void badInputIsRejected() throws Exception {
        String invalidCron = "{\"name\":\"a\",\"type\":\"NOOP\",\"cron\":\"nonsense\"}";
        String everySecond = "{\"name\":\"b\",\"type\":\"NOOP\",\"cron\":\"* * * * * *\"}";
        String badZone = "{\"name\":\"c\",\"type\":\"NOOP\",\"cron\":\"0 0 3 * * *\",\"timezone\":\"Mars/Phobos\"}";
        for (String body : List.of(invalidCron, everySecond, badZone)) {
            mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void duplicateNamesConflict() throws Exception {
        create("nightly", "0 0 3 * * *");
        String body = "{\"name\":\"nightly\",\"type\":\"NOOP\",\"cron\":\"0 0 4 * * *\"}";
        mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void pauseAndResume() throws Exception {
        String id = create("pausable", "0 */5 * * * *").get("id").asText();

        mvc.perform(post("/api/schedules/" + id + "/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(post("/api/schedules/" + id + "/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(post("/api/schedules/" + UUID.randomUUID() + "/pause"))
                .andExpect(status().isNotFound());
    }

    @Test
    void triggerCreatesAJobLinkedToTheSchedule() throws Exception {
        String id = create("manual", "0 0 3 * * *").get("id").asText();

        mvc.perform(post("/api/schedules/" + id + "/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("NOOP"))
                .andExpect(jsonPath("$.scheduleId").value(id));
        assertThat(jobsOf(id)).isEqualTo(1);
    }

    @Test
    void deletingAScheduleKeepsItsJobs() throws Exception {
        String id = create("temporary", "0 0 3 * * *").get("id").asText();
        String jobJson = mvc.perform(post("/api/schedules/" + id + "/trigger"))
                .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(mapper.readTree(jobJson).get("id").asText());

        mvc.perform(delete("/api/schedules/" + id)).andExpect(status().isOk());

        mvc.perform(get("/api/schedules/" + id)).andExpect(status().isNotFound());
        assertThat(jobs.findById(jobId)).isPresent();
        assertThat(jobs.findById(jobId).orElseThrow().scheduleId()).isNull();
    }

    @Test
    void dueScheduleFiresOnceAndMissedRunsAreNotReplayed() throws Exception {
        String id = create("missed", "0 * * * * *").get("id").asText(); // every minute
        makeDue(id); // pretend the workers were down for an hour

        assertThat(service.fireDueSchedules()).isEqualTo(1);
        assertThat(jobsOf(id)).isEqualTo(1); // one job, not 60

        JsonNode after = mapper.readTree(mvc.perform(get("/api/schedules/" + id))
                .andReturn().getResponse().getContentAsString());
        assertThat(Instant.parse(after.get("nextRunAt").asText())).isAfter(Instant.now());
        assertThat(after.get("lastRunAt").isNull()).isFalse();

        assertThat(service.fireDueSchedules()).isZero(); // nothing is due any more
        assertThat(jobsOf(id)).isEqualTo(1);
    }

    @Test
    void pausedScheduleIsNotFired() throws Exception {
        String id = create("quiet", "0 * * * * *").get("id").asText();
        mvc.perform(post("/api/schedules/" + id + "/pause")).andExpect(status().isOk());
        makeDue(id);

        assertThat(service.fireDueSchedules()).isZero();
        assertThat(jobsOf(id)).isZero();
    }

    @Test
    void simultaneousTicksFireADueScheduleExactlyOnce() throws Exception {
        String id = create("contended", "0 * * * * *").get("id").asText();
        makeDue(id);

        int ticks = 4;
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(ticks);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            results.add(pool.submit(() -> {
                startGun.await();
                return service.fireDueSchedules();
            }));
        }
        startGun.countDown();
        int totalFired = 0;
        for (Future<Integer> result : results) {
            totalFired += result.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(totalFired).isEqualTo(1);
        assertThat(jobsOf(id)).isEqualTo(1);
    }
}
