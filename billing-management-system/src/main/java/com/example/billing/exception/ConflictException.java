package com.example.billing.exception;

/**
 * Thrown when an operation conflicts with existing data
 * (duplicate email, deleting a client that still has projects). Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
