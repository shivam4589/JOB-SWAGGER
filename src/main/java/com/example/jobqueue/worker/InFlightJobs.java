package com.example.jobqueue.worker;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The jobs this process is running right now, with the thread running each one. The heartbeat uses it to know which
 * locks to refresh, and to interrupt the right thread when cancellation was requested for a job.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class InFlightJobs {

    public static final class Entry {
        private final String workerId;
        private final Thread thread;
        private volatile boolean cancelRequested;
        private boolean active = true; // guarded by synchronized (this)

        private Entry(String workerId, Thread thread) {
            this.workerId = workerId;
            this.thread = thread;
        }

        public String workerId() {
            return workerId;
        }

        public boolean cancelRequested() {
            return cancelRequested;
        }
    }

    private final ConcurrentHashMap<UUID, Entry> jobs = new ConcurrentHashMap<>();

    /** Registers a job that the calling thread is about to run. */
    public Entry add(UUID jobId, String workerId) {
        Entry entry = new Entry(workerId, Thread.currentThread());
        jobs.put(jobId, entry);
        return entry;
    }

    public void remove(UUID jobId) {
        Entry entry = jobs.remove(jobId);
        if (entry != null) {
            synchronized (entry) {
                entry.active = false; // from now on this entry can no longer interrupt its thread
            }
        }
    }

    public Map<UUID, Entry> snapshot() {
        return Map.copyOf(jobs);
    }

    /**
     * Interrupts the thread that is running the job. Returns false if the job is no longer running here. The check
     * and the interrupt happen under the entry's lock, so a thread that has already moved on to another job is
     * never interrupted by mistake.
     */
    public boolean requestCancel(UUID jobId) {
        Entry entry = jobs.get(jobId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (!entry.active) {
                return false;
            }
            entry.cancelRequested = true;
            entry.thread.interrupt();
            return true;
        }
    }
}
