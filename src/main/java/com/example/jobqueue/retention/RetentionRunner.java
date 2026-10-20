package com.example.jobqueue.retention;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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

/** Runs the retention cleanup in the background of every worker process (concurrent runs are harmless: SKIP LOCKED). */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class RetentionRunner {

    private static final Logger log = LoggerFactory.getLogger(RetentionRunner.class);

    private final RetentionService service;
    private final RetentionProperties props;
    private final Counter deletedCounter;
    private ScheduledExecutorService scheduler;

    public RetentionRunner(RetentionService service, RetentionProperties props, MeterRegistry meters) {
        this.service = service;
        this.props = props;
        this.deletedCounter = Counter.builder("jobqueue.retention.deleted")
                .description("Old finished jobs deleted by the retention cleanup")
                .register(meters);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.enabled()) {
            log.info("Retention cleanup is disabled");
            return;
        }
        long interval = Math.max(1, props.intervalMinutes());
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "retention");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::run, 1, interval, TimeUnit.MINUTES);
        log.info("Retention cleanup started: SUCCEEDED jobs kept {} days, DEAD/CANCELLED {} days, runs every {} min",
                props.succeededDays(), props.failedDays(), interval);
    }

    private void run() {
        try {
            int deleted = service.purgeOnce();
            if (deleted > 0) {
                deletedCounter.increment(deleted);
                log.info("Retention cleanup deleted {} old job(s)", deleted);
            }
        } catch (Exception e) {
            // never let an exception cancel the schedule
            log.error("Retention cleanup error: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
