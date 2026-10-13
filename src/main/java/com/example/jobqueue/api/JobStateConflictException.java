package com.example.jobqueue.api;

/** The job exists, but its current status does not allow the requested action (HTTP 409). */
public class JobStateConflictException extends RuntimeException {
    public JobStateConflictException(String message) {
        super(message);
    }
}
