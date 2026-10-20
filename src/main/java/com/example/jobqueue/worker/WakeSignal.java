package com.example.jobqueue.worker;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Lets idle worker threads sleep until something happens. A thread reads generation() BEFORE it looks for work
 * and passes that number to awaitChange(), so a signal that arrives between "no work found" and "going to sleep"
 * is never lost.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.worker.enabled", havingValue = "true")
public class WakeSignal {

    private final Object lock = new Object();
    private long generation;

    public long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    public void signal() {
        synchronized (lock) {
            generation++;
            lock.notifyAll();
        }
    }

    /** Returns when signal() has been called since the given generation, or after the timeout. */
    public void awaitChange(long seenGeneration, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (lock) {
            while (generation == seenGeneration) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
        }
    }
}
