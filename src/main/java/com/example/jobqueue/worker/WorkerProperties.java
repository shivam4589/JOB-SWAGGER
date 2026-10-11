package com.example.jobqueue.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jobqueue.worker.enabled              run polling worker threads (and the reaper) in this process
 * jobqueue.worker.concurrency          number of polling threads
 * jobqueue.worker.poll-interval-ms     sleep between polls when the queue is empty
 * jobqueue.worker.backoff-base-seconds first retry delay; doubles with every failed attempt
 * jobqueue.worker.backoff-max-seconds  cap for the retry delay
 * jobqueue.worker.lock-timeout-seconds a RUNNING job locked longer than this is considered
 *                                      abandoned. Must be longer than your slowest job.
 * jobqueue.worker.reaper-interval-ms   how often the reaper looks for abandoned jobs
 */
@ConfigurationProperties(prefix = "jobqueue.worker")
public record WorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("2") int concurrency,
        @DefaultValue("500") long pollIntervalMs,
        @DefaultValue("2") long backoffBaseSeconds,
        @DefaultValue("300") long backoffMaxSeconds,
        @DefaultValue("120") long lockTimeoutSeconds,
        @DefaultValue("15000") long reaperIntervalMs) {
}
