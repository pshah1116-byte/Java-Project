package com.example.billing.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The JSON body returned for every error, for example:
 * <pre>
 * {
 *   "timestamp": "2026-10-16T05:00:00Z",
 *   "status": 409,
 *   "error": "Conflict",
 *   "message": "A client with email 'alex@example.com' already exists.",
 *   "path": "/api/clients"
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {
}
