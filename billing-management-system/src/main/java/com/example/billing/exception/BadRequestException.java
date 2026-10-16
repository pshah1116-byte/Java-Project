package com.example.billing.exception;

/**
 * Thrown when a request is invalid or breaks a business rule
 * (bad ID format, invalid status transition, wrong dates, ...). Mapped to HTTP 400.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
