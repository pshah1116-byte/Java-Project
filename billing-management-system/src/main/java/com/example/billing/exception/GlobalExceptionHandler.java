package com.example.billing.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts exceptions into consistent JSON error responses (see {@link ErrorResponse}).
 * Stack traces are logged to the IntelliJ Run console but never sent to the browser.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    /** Safety net: the unique index on clients.email rejected a duplicate. */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "A record with the same unique value (for example, email) already exists.",
                request, null);
    }

    /** @Valid failed on a request body: return every field error. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        String message = fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "Validation failed. " + message, request, fieldErrors);
    }

    /** Malformed JSON, an unknown enum value, or a badly formatted date/number. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String message = "Request body is not valid JSON.";
        if (ex.getCause() instanceof InvalidFormatException ife) {
            String field = fieldName(ife);
            Class<?> target = ife.getTargetType();
            if (target != null && target.isEnum()) {
                message = "Invalid value '" + ife.getValue() + "' for field '" + field
                        + "'. Allowed values: " + enumValues(target) + ".";
            } else if (target == LocalDate.class) {
                message = "Invalid date '" + ife.getValue() + "' for field '" + field
                        + "'. Use the format YYYY-MM-DD.";
            } else {
                message = "Invalid value '" + ife.getValue() + "' for field '" + field + "'.";
            }
        } else if (ex.getCause() instanceof JsonMappingException jme && !jme.getPath().isEmpty()) {
            message = "Invalid value for field '" + fieldName(jme) + "'.";
        }
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    /** A query/path parameter has the wrong type, e.g. ?status=FOO. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        Class<?> type = ex.getRequiredType();
        String message = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'.";
        if (type != null && type.isEnum()) {
            message += " Allowed values: " + enumValues(type) + ".";
        }
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED,
                "HTTP method " + ex.getMethod() + " is not supported for this URL.", request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type must be application/json.", request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "No endpoint or file found for this URL.", request, null);
    }

    /** MongoDB is not running or not reachable. */
    @ExceptionHandler({DataAccessResourceFailureException.class, MongoTimeoutException.class,
            MongoSocketException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseDown(Exception ex, HttpServletRequest request) {
        log.error("MongoDB is not reachable: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE,
                "Cannot connect to MongoDB. Make sure the MongoDB server is running "
                        + "(default address: mongodb://localhost:27017), then try again.",
                request, null);
    }

    /** Anything unexpected: log details, return a generic message (no stack trace). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected server error occurred. Check the IntelliJ Run console for details.",
                request, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request, Map<String, String> fieldErrors) {
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(),
                message, request.getRequestURI(), fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    private static String fieldName(JsonMappingException ex) {
        return ex.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    private static String enumValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.joining(", "));
    }
}
