package com.example.jobqueue.worker;

import com.example.jobqueue.repo.JobRepository;
import jakarta.annotation.PreDestroy;
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
 * Periodically reclaims jobs abandoned by crashed workers. Every worker process runs one;
 * that is safe because the reclaim query uses SKIP LOCKED.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class StuckJobReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckJobReaper.class);

    private final JobRepository repository;
    private final WorkerProperties props;
    private ScheduledExecutorService scheduler;

    public StuckJobReaper(JobRepository repository, WorkerProperties props) {
        this.repository = repository;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        long interval = Math.max(500, props.reaperIntervalMs());
        scheduler.scheduleWithFixedDelay(this::reap, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Reaper started: lock timeout {}s, interval {} ms", props.lockTimeoutSeconds(), interval);
    }

    private void reap() {
        try {
            int reclaimed = repository.reapStuckJobs(props.lockTimeoutSeconds());
            if (reclaimed > 0) {
                log.warn("Reaper reclaimed {} abandoned job(s)", reclaimed);
            }
        } catch (Exception e) {
            // never let an exception cancel the schedule
            log.error("Reaper error: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
