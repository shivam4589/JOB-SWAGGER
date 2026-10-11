package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.repo.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves the core guarantee: concurrent workers never claim the same job twice. */
@Testcontainers
@SpringBootTest
class ConcurrentClaimTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JobRepository repository;

    @Autowired
    ObjectMapper mapper;

    @Test
    void tenWorkersClaimEveryJobExactlyOnce() throws Exception {
        int jobCount = 500;
        int workers = 10;
        ObjectNode payload = mapper.createObjectNode();

        Set<UUID> inserted = new HashSet<>();
        for (int i = 0; i < jobCount; i++) {
            inserted.add(repository.insert(UUID.randomUUID(), "NOOP", payload, 5, 0, 5).id());
        }

        ConcurrentLinkedQueue<UUID> claimed = new ConcurrentLinkedQueue<>();
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<?>> futures = new ArrayList<>();

        for (int w = 0; w < workers; w++) {
            String workerId = "test-worker-" + w;
            futures.add(pool.submit(() -> {
                startGun.await();
                Optional<Job> job;
                while ((job = repository.claimNext(workerId)).isPresent()) {
                    claimed.add(job.get().id());
                }
                return null;
            }));
        }

        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(claimed).hasSize(jobCount);                       // nothing lost
        assertThat(new HashSet<>(claimed)).isEqualTo(inserted);      // nothing duplicated
    }
}
