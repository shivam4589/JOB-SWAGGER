package com.example.jobqueue.api;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStats;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JobService {

    private static final int DEFAULT_PRIORITY = 5;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final JobRepository repository;

    public JobService(JobRepository repository) {
        this.repository = repository;
    }

    public record PagedJobs(List<Job> items, long total) {
    }

    public Job submit(SubmitJobRequest req) {
        return repository.insert(
                UUID.randomUUID(),
                req.type(),
                req.payload(),
                req.priority() != null ? req.priority() : DEFAULT_PRIORITY,
                req.delaySeconds() != null ? req.delaySeconds() : 0,
                req.maxAttempts() != null ? req.maxAttempts() : DEFAULT_MAX_ATTEMPTS);
    }

    public Job get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    public PagedJobs list(JobStatus status, String type, int limit, int offset) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        List<Job> items = repository.list(status, type, safeLimit, safeOffset);
        return new PagedJobs(items, repository.count(status, type));
    }

    public List<JobAttempt> attempts(UUID id) {
        get(id); // 404 if the job does not exist
        return repository.attemptsFor(id);
    }

    /** Only PENDING jobs can be cancelled; a job a worker already claimed is left alone. */
    public Job cancel(UUID id) {
        return repository.cancelIfPending(id)
                .orElseThrow(() -> notFoundOrConflict(id, "cancelled", "PENDING"));
    }

    /** Puts a DEAD job back in the queue with a fresh attempt budget. Its attempt history is kept. */
    public Job retry(UUID id) {
        return repository.requeueIfDead(id)
                .orElseThrow(() -> notFoundOrConflict(id, "retried", "DEAD"));
    }

    public JobStats stats() {
        Map<String, Long> byStatus = repository.statusCounts();
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        JobRepository.RecentStats recent = repository.recentStats();
        return new JobStats(new LinkedHashMap<>(byStatus), total,
                recent.succeededLastMinute(), recent.oldestDuePendingSeconds());
    }

    private RuntimeException notFoundOrConflict(UUID id, String action, String requiredStatus) {
        return repository.findById(id)
                .<RuntimeException>map(job -> new JobStateConflictException(
                        "Job " + id + " is " + job.status() + "; only " + requiredStatus
                                + " jobs can be " + action))
                .orElseGet(() -> new JobNotFoundException(id));
    }
}
