package com.example.jobqueue.domain;

import java.util.Map;

/**
 * Snapshot for dashboards.
 * oldestDuePendingSeconds is how long the longest-waiting runnable job has waited; null if nothing is waiting.
 */
public record JobStats(
        Map<String, Long> byStatus,
        long total,
        long succeededLastMinute,
        Double oldestDuePendingSeconds) {
}
