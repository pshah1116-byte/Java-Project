package com.example.billing.exception;

/** Thrown when a record with the given ID does not exist. Mapped to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, String id) {
        super(resourceName + " not found with id '" + id + "'.");
    }
}
