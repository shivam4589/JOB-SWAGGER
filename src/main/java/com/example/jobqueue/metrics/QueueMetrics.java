package com.example.jobqueue.metrics;

import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Queue-wide gauges read from Postgres: jobs per status and the age of the longest-waiting runnable job.
 * Registered only in the API process (not in workers), so Prometheus sees one series instead of one per
 * container. Values are cached for a few seconds so scraping never hammers the database.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "false", matchIfMissing = true)
public class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);
    private static final long CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final JobRepository repository;

    private Map<String, Long> byStatus = Map.of();
    private double oldestPendingSeconds = 0;
    private long loadedAtNanos;
    private boolean loadedOnce;

    public QueueMetrics(JobRepository repository, MeterRegistry registry) {
        this.repository = repository;
        for (JobStatus status : JobStatus.values()) {
            Gauge.builder("jobqueue.jobs", this, metrics -> metrics.count(status.name()))
                    .description("Number of jobs in each status")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder("jobqueue.oldest.pending.age", this, QueueMetrics::oldestPending)
                .description("How long the longest-waiting runnable job has been waiting (0 if none)")
                .baseUnit("seconds")
                .register(registry);
    }

    private double count(String status) {
        refreshIfStale();
        return byStatus.getOrDefault(status, 0L);
    }

    private double oldestPending() {
        refreshIfStale();
        return oldestPendingSeconds;
    }

    private synchronized void refreshIfStale() {
        long now = System.nanoTime();
        if (loadedOnce && now - loadedAtNanos < CACHE_NANOS) {
            return;
        }
        try {
            byStatus = repository.statusCounts();
            Double oldest = repository.recentStats().oldestDuePendingSeconds();
            oldestPendingSeconds = oldest == null ? 0 : oldest;
            loadedOnce = true;
        } catch (Exception e) {
            // keep serving the last known values and retry after the cache interval
            log.warn("Could not refresh queue metrics: {}", e.toString());
        }
        loadedAtNanos = now;
    }
}
