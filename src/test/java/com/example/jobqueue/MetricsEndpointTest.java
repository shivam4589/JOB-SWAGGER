package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The Prometheus endpoint exposes the queue depth gauges, read from the real database. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class MetricsEndpointTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    private static double gauge(String body, String name, String status) {
        Matcher matcher = Pattern
                .compile("^" + Pattern.quote(name) + "\\{status=\"" + status + "\"[^}]*}\\s+(\\S+)", Pattern.MULTILINE)
                .matcher(body);
        assertThat(matcher.find()).as("metric %s for status %s", name, status).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    @Test
    void prometheusEndpointReportsJobsPerStatus() throws Exception {
        for (int i = 0; i < 3; i++) {
            jobs.insert(UUID.randomUUID(), "T", mapper.createObjectNode(), 5, 0, 5);
        }
        UUID cancelled = jobs.insert(UUID.randomUUID(), "T", mapper.createObjectNode(), 5, 0, 5).id();
        jobs.cancelIfPending(cancelled);

        String body = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(gauge(body, "jobqueue_jobs", "PENDING")).isEqualTo(3.0);
        assertThat(gauge(body, "jobqueue_jobs", "CANCELLED")).isEqualTo(1.0);
        assertThat(gauge(body, "jobqueue_jobs", "RUNNING")).isEqualTo(0.0);
        assertThat(body).contains("jobqueue_oldest_pending_age_seconds");
    }
}
