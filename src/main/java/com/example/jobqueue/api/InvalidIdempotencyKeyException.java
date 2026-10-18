package com.example.jobqueue.api;

/** The Idempotency-Key header is not acceptable (HTTP 400). */
public class InvalidIdempotencyKeyException extends RuntimeException {
    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key must be 1-200 characters: letters, digits, '.', '_', ':', '~' or '-'");
    }
}
