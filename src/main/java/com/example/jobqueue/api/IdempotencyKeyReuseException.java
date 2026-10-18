package com.example.jobqueue.api;

/** The Idempotency-Key was already used for a different request (HTTP 422). */
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("Idempotency key '" + key + "' was already used with a different request");
    }
}
