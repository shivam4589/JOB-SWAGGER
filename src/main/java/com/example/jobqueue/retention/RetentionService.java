package com.example.jobqueue.retention;

import com.example.jobqueue.repo.JobRepository;
import org.springframework.stereotype.Service;

@Service
public class RetentionService {

    /** Upper bound per run, so one run can never loop for very long. */
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final JobRepository repository;
    private final RetentionProperties props;

    public RetentionService(JobRepository repository, RetentionProperties props) {
        this.repository = repository;
        this.props = props;
    }

    /** Deletes expired finished jobs in batches and returns how many were removed. */
    public int purgeOnce() {
        int batchSize = Math.max(1, props.batchSize());
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            int deleted = repository.deleteExpired(props.succeededDays(), props.failedDays(), batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        return total;
    }
}
