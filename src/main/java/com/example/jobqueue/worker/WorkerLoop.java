package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.repo.JobRepository;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Polling worker: N threads, each repeatedly claims one job and runs its handler.
 * Delivery is at-least-once: a job can run again if its worker dies, so handlers
 * should be idempotent.
 * Only created when jobqueue.worker.enabled=true, so the API container stays a pure API.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class WorkerLoop {

    private static final Logger log = LoggerFactory.getLogger(WorkerLoop.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JobRepository repository;
    private final JobHandlerRegistry registry;
    private final WorkerProperties props;
    private final BackoffPolicy backoff;

    private ExecutorService executor;
    private volatile boolean running;

    public WorkerLoop(JobRepository repository, JobHandlerRegistry registry, WorkerProperties props) {
        this.repository = repository;
        this.registry = registry;
        this.props = props;
        this.backoff = new BackoffPolicy(props.backoffBaseSeconds(), props.backoffMaxSeconds());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = true;
        int threads = Math.max(1, props.concurrency());
        String prefix = hostName() + "-" + UUID.randomUUID().toString().substring(0, 8);
        executor = Executors.newFixedThreadPool(threads);
        for (int i = 1; i <= threads; i++) {
            String workerId = prefix + "-t" + i;
            executor.submit(() -> runLoop(workerId));
        }
        log.info("Worker started: {} thread(s), poll interval {} ms", threads, props.pollIntervalMs());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker stopped");
    }

    private void runLoop(String workerId) {
        while (running) {
            try {
                Optional<Job> claimed = repository.claimNext(workerId);
                if (claimed.isPresent()) {
                    execute(claimed.get(), workerId);
                } else {
                    Thread.sleep(props.pollIntervalMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // e.g. database briefly unavailable: log and back off, never kill the thread
                log.error("Worker {} poll error: {}", workerId, e.toString());
                try {
                    Thread.sleep(Math.max(1000, props.pollIntervalMs()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void execute(Job job, String workerId) {
        long attemptId = repository.startAttempt(job.id(), workerId);
        try {
            JobHandler handler = registry.find(job.type()).orElseThrow(
                    () -> new IllegalStateException("No handler registered for job type " + job.type()));
            handler.handle(job);
            repository.finishAttempt(attemptId, true, null);
            if (repository.markSucceeded(job.id(), workerId) == 0) {
                log.warn("Job {} finished but this worker no longer holds its lock (reclaimed by the reaper?)",
                        job.id());
            } else {
                log.info("Job {} ({}) succeeded on attempt {} by {}",
                        job.id(), job.type(), job.attempts(), workerId);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            handleFailure(job, workerId, attemptId, truncate(e.toString()));
        }
    }

    private void handleFailure(Job job, String workerId, long attemptId, String error) {
        repository.finishAttempt(attemptId, false, error);
        if (job.attempts() >= job.maxAttempts()) {
            repository.markDead(job.id(), workerId, error);
            log.error("Job {} ({}) is DEAD after {} attempts: {}",
                    job.id(), job.type(), job.attempts(), error);
        } else {
            double delay = backoff.delaySeconds(job.attempts());
            repository.scheduleRetry(job.id(), workerId, error, delay);
            log.warn("Job {} ({}) failed attempt {}/{}, retrying in {}s: {}",
                    job.id(), job.type(), job.attempts(), job.maxAttempts(),
                    String.format("%.1f", delay), error);
        }
    }

    private static String truncate(String s) {
        return s.length() <= MAX_ERROR_LENGTH ? s : s.substring(0, MAX_ERROR_LENGTH);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
