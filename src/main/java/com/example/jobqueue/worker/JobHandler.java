package com.example.jobqueue.worker;

import com.example.jobqueue.domain.Job;

/**
 * Implement this and register it as a Spring bean to teach the queue a new job type.
 * Throw any exception to signal failure.
 */
public interface JobHandler {

    /** The job type this handler processes, e.g. "SEND_EMAIL". */
    String type();

    void handle(Job job) throws Exception;
}
