package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.jobqueue.api.JobService;
import com.example.jobqueue.api.SubmitJobRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Idempotency-Key behaviour through the HTTP layer and under concurrency, against a real Postgres. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JobService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    private ResultActions send(String body, String key) throws Exception {
        var request = post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private String idOf(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private long jobCount() {
        return jdbc.sql("SELECT count(*) FROM jobs").query(Long.class).single();
    }

    @Test
    void withoutAKeyEveryRequestCreatesAJob() throws Exception {
        String body = "{\"type\":\"T\",\"payload\":{\"a\":1}}";
        String first = idOf(send(body, null).andExpect(status().isCreated()));
        String second = idOf(send(body, null).andExpect(status().isCreated()));

        assertThat(first).isNotEqualTo(second);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test
    void sameKeyAndSameRequestReturnsTheOriginalJob() throws Exception {
        String body = "{\"type\":\"T\",\"payload\":{\"a\":1}}";

        String first = idOf(send(body, "order-1001").andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")));
        String second = idOf(send(body, "order-1001").andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true")));

        assertThat(second).isEqualTo(first);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void fieldOrderAndExplicitDefaultsDoNotMatter() throws Exception {
        String first = idOf(send("{\"type\":\"T\",\"payload\":{\"a\":1,\"b\":2}}", "k-order")
                .andExpect(status().isCreated()));
        String second = idOf(send("{\"payload\":{\"b\":2,\"a\":1},\"type\":\"T\",\"priority\":5,"
                + "\"delaySeconds\":0,\"maxAttempts\":5}", "k-order")
                .andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithADifferentRequestIsRejected() throws Exception {
        send("{\"type\":\"A\",\"payload\":{}}", "k-reuse").andExpect(status().isCreated());

        send("{\"type\":\"B\",\"payload\":{}}", "k-reuse").andExpect(status().isUnprocessableEntity());
        send("{\"type\":\"A\",\"payload\":{\"x\":1}}", "k-reuse").andExpect(status().isUnprocessableEntity());
        send("{\"type\":\"A\",\"payload\":{},\"priority\":9}", "k-reuse").andExpect(status().isUnprocessableEntity());

        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void differentKeysCreateDifferentJobs() throws Exception {
        String body = "{\"type\":\"T\",\"payload\":{}}";
        String first = idOf(send(body, "key-a").andExpect(status().isCreated()));
        String second = idOf(send(body, "key-b").andExpect(status().isCreated()));

        assertThat(first).isNotEqualTo(second);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test
    void malformedKeysAreRejectedAndBlankMeansNoKey() throws Exception {
        String body = "{\"type\":\"T\",\"payload\":{}}";
        send(body, "bad key with spaces").andExpect(status().isBadRequest());
        send(body, "x".repeat(201)).andExpect(status().isBadRequest());
        send(body, "   ").andExpect(status().isCreated()); // blank header is treated as "no key"

        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void simultaneousRequestsWithOneKeyCreateExactlyOneJob() throws Exception {
        int threads = 8;
        SubmitJobRequest request = new SubmitJobRequest("T", mapper.createObjectNode(), null, null, null);
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<JobService.SubmitResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startGun.await();
                return service.submit(request, "race-key");
            }));
        }
        startGun.countDown();

        int created = 0;
        Set<UUID> jobIds = new HashSet<>();
        for (Future<JobService.SubmitResult> future : futures) {
            JobService.SubmitResult result = future.get(30, TimeUnit.SECONDS);
            if (result.created()) {
                created++;
            }
            jobIds.add(result.job().id());
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);   // exactly one request made the job
        assertThat(jobIds).hasSize(1);      // everyone got the same job back
        assertThat(jobCount()).isEqualTo(1);
    }
}
