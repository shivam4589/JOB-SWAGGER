package com.example.jobqueue.retention;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * jobqueue.retention.enabled          delete old finished jobs automatically
 * jobqueue.retention.succeeded-days   keep SUCCEEDED jobs this many days
 * jobqueue.retention.failed-days      keep DEAD and CANCELLED jobs this many days (they are worth investigating)
 * jobqueue.retention.interval-minutes how often the cleanup runs
 * jobqueue.retention.batch-size       jobs deleted per statement, so cleanup never holds big locks
 */
@ConfigurationProperties(prefix = "jobqueue.retention")
public record RetentionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("7") int succeededDays,
        @DefaultValue("30") int failedDays,
        @DefaultValue("60") int intervalMinutes,
        @DefaultValue("1000") int batchSize) {
}
