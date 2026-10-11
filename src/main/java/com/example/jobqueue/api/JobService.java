package com.example.jobqueue.api;

import com.example.jobqueue.domain.Job;
import com.example.jobqueue.domain.JobAttempt;
import com.example.jobqueue.domain.JobStatus;
import com.example.jobqueue.repo.JobRepository;
import java.util.List;
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

    public List<JobAttempt> attempts(UUID id) {
        get(id); // 404 if the job does not exist
        return repository.attemptsFor(id);
    }

    public List<Job> list(JobStatus status, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return repository.list(status, safeLimit);
    }
}
