package com.example.jobqueue.schedule;

import com.example.jobqueue.worker.WorkerProperties;
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

/** Ticks every few seconds in each worker process; see ScheduleService.fireDueSchedules for why that is safe. */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class ScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRunner.class);

    private final ScheduleService service;
    private final WorkerProperties props;
    private ScheduledExecutorService scheduler;

    public ScheduleRunner(ScheduleService service, WorkerProperties props) {
        this.service = service;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        long interval = Math.max(500, props.schedulerIntervalMs());
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Scheduler started: checking recurring schedules every {} ms", interval);
    }

    private void tick() {
        try {
            int fired = service.fireDueSchedules();
            if (fired > 0) {
                log.info("Scheduler created jobs for {} due schedule(s)", fired);
            }
        } catch (Exception e) {
            // never let an exception cancel the schedule
            log.error("Scheduler error: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
