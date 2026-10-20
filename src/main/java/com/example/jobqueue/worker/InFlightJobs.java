package com.example.jobqueue.worker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The jobs this process is running right now (job id -> worker thread id), so the heartbeat knows what to refresh. */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class InFlightJobs {

    private final ConcurrentHashMap<UUID, String> jobs = new ConcurrentHashMap<>();

    public void add(UUID jobId, String workerId) {
        jobs.put(jobId, workerId);
    }

    public void remove(UUID jobId) {
        jobs.remove(jobId);
    }

    public Map<UUID, String> snapshot() {
        return Map.copyOf(jobs);
    }
}
