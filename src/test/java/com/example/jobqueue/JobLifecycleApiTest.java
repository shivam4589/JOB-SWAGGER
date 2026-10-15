package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
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

/** Cancel, retry, stats, filtering/paging and CORS, exercised through the real HTTP layer and a real Postgres. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class JobLifecycleApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    private String submit(String type) throws Exception {
        String body = "{\"type\":\"" + type + "\",\"payload\":{\"k\":1}}";
        String json = mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("id").asText();
    }

    @Test
    void pendingJobCanBeCancelledOnlyOnce() throws Exception {
        String id = submit("T");

        mvc.perform(delete("/api/jobs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(delete("/api/jobs/" + id)).andExpect(status().isConflict());
        mvc.perform(delete("/api/jobs/" + UUID.randomUUID())).andExpect(status().isNotFound());

        assertThat(repository.claimNext("w1")).isEmpty(); // a cancelled job is never handed to a worker
    }

    @Test
    void runningJobCannotBeCancelled() throws Exception {
        String id = submit("T");
        repository.claimNext("w1").orElseThrow();

        mvc.perform(delete("/api/jobs/" + id)).andExpect(status().isConflict());
    }

    @Test
    void deadJobCanBeRetriedWithFreshAttempts() throws Exception {
        Job job = repository.insert(UUID.randomUUID(), "T", mapper.createObjectNode(), 5, 0, 1);
        repository.claimNext("w1").orElseThrow();
        repository.markDead(job.id(), "w1", "gave up");

        mvc.perform(post("/api/jobs/" + job.id() + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0));
        mvc.perform(post("/api/jobs/" + job.id() + "/retry")).andExpect(status().isConflict());
        mvc.perform(post("/api/jobs/" + UUID.randomUUID() + "/retry")).andExpect(status().isNotFound());

        assertThat(repository.claimNext("w2")).isPresent();
    }

    @Test
    void statsCountEveryStatus() throws Exception {
        submit("A");
        submit("A");
        String cancelled = submit("B");
        mvc.perform(delete("/api/jobs/" + cancelled)).andExpect(status().isOk());

        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.byStatus.PENDING").value(2))
                .andExpect(jsonPath("$.byStatus.CANCELLED").value(1))
                .andExpect(jsonPath("$.byStatus.RUNNING").value(0))
                .andExpect(jsonPath("$.oldestDuePendingSeconds").isNumber());
    }

    @Test
    void statsReportNoWaitWhenNothingIsPending() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.oldestDuePendingSeconds").doesNotExist());
    }

    @Test
    void listSupportsTypeFilterAndPaging() throws Exception {
        submit("A");
        submit("A");
        submit("B");

        mvc.perform(get("/api/jobs").param("type", "A").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "2"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/jobs").param("type", "A").param("limit", "1").param("offset", "1"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/jobs").param("type", "A").param("limit", "1").param("offset", "2"))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/jobs"))
                .andExpect(header().string("X-Total-Count", "3"));
    }

    @Test
    void corsAllowsLocalFrontendAndRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/jobs")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mvc.perform(options("/api/jobs")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }
}
