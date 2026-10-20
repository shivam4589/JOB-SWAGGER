package com.example.jobqueue.worker;

import com.example.jobqueue.repo.JobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * While a job runs, its worker keeps refreshing the job's lock time. The reaper only reclaims jobs whose lock has
 * gone stale, so a long-running job is never mistaken for an abandoned one, and a crashed worker (which stops
 * refreshing) is still detected after the lock timeout.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class LockHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LockHeartbeat.class);

    private final JobRepository repository;
    private final InFlightJobs inFlight;
    private final WorkerProperties props;
    private final Counter lostCounter;
    private ScheduledExecutorService scheduler;

    public LockHeartbeat(JobRepository repository, InFlightJobs inFlight, WorkerProperties props,
                         MeterRegistry meters) {
        this.repository = repository;
        this.inFlight = inFlight;
        this.props = props;
        this.lostCounter = Counter.builder("jobqueue.heartbeat.lost")
                .description("Heartbeats for jobs that were no longer owned by this worker")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long interval = Math.max(200, props.heartbeatIntervalMs());
        if (interval * 2 > props.lockTimeoutSeconds() * 1000) {
            log.warn("Heartbeat interval ({} ms) should be well below half of the lock timeout ({} s)",
                    interval, props.lockTimeoutSeconds());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lock-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::beat, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Lock heartbeat started: refreshing running jobs every {} ms", interval);
    }

    private void beat() {
        for (Map.Entry<UUID, String> entry : inFlight.snapshot().entrySet()) {
            try {
                if (repository.touchLock(entry.getKey(), entry.getValue()) == 0) {
                    lostCounter.increment();
                    log.warn("Job {} is no longer locked by {} (reclaimed by the reaper?)",
                            entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                // a database hiccup must not stop the heartbeat; the next beat tries again
                log.warn("Heartbeat for job {} failed: {}", entry.getKey(), e.toString());
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
