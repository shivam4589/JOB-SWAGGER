package com.example.jobqueue.worker;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff: base, 2*base, 4*base, ... capped at max, then +/-25% random jitter
 * so a burst of failures does not retry in lockstep.
 */
public class BackoffPolicy {

    private final long baseSeconds;
    private final long maxSeconds;

    public BackoffPolicy(long baseSeconds, long maxSeconds) {
        this.baseSeconds = Math.max(0, baseSeconds);
        this.maxSeconds = Math.max(this.baseSeconds, maxSeconds);
    }

    /** Delay before the retry that follows the given failed attempt (1 = first attempt), no jitter. */
    public long baseDelaySeconds(int failedAttempt) {
        long delay = baseSeconds;
        for (int i = 1; i < failedAttempt && delay < maxSeconds; i++) {
            delay *= 2;
        }
        return Math.min(delay, maxSeconds);
    }

    public double delaySeconds(int failedAttempt) {
        double jitter = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return baseDelaySeconds(failedAttempt) * jitter;
    }
}
