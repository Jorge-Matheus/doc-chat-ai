package com.docchat.common.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every API error as an RFC 9457 Problem Details body with three extra members: a
 * machine-readable {@code code}, a {@code timestamp} and the request's {@code correlationId}.
 *
 * <p>Unexpected exceptions are logged with their stack trace and answered with a generic message,
 * so internal details such as SQL or provider responses never reach the client. Security failures
 * are routed here from the filter chain, so they use the same format.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String UNEXPECTED_ERROR_DETAIL = "An unexpected error occurred.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        return respond(ex, ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    /** An expected failure with a clear cause, so it is logged without a stack trace. */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<Object> handleDatabaseUnavailable(Exception ex, WebRequest request) {
        log.warn("Database unavailable: {}", ex.getClass().getSimpleName());
        return respond(
                ex,
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE",
                "The database is not available right now. Try again in a moment.",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        // The account could not be read, which says nothing about the email or the password.
        if (ex instanceof InternalAuthenticationServiceException) {
            return handleDatabaseUnavailable(ex, request);
        }
        if (ex instanceof BadCredentialsException) {
            return respond(
                    ex,
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "The email or password is incorrect.",
                    request);
        }
        return respond(ex, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(ex, HttpStatus.FORBIDDEN, "FORBIDDEN", "This action is not allowed.", request);
    }

    @ExceptionHandler(MultipartException.class)
    ResponseEntity<Object> handleMalformedUpload(MultipartException ex, WebRequest request) {
        return respond(ex, HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", "The upload could not be read.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing the request", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, UNEXPECTED_ERROR_DETAIL);
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        // Rejected values are left out on purpose, because they can be passwords.
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, "The requested resource was not found.");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            addStandardProperties(problem, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(
            Exception ex, HttpStatus status, String code, String detail, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setProperty("code", code);
        return handleExceptionInternal(ex, body, new HttpHeaders(), status, request);
    }

    private static void addStandardProperties(ProblemDetail problem, HttpStatusCode statusCode) {
        Map<String, Object> existing = problem.getProperties();
        if (existing == null || !existing.containsKey("code")) {
            HttpStatus status = HttpStatus.resolve(statusCode.value());
            problem.setProperty("code", status != null ? status.name() : "HTTP_" + statusCode.value());
        }
        problem.setProperty("timestamp", Instant.now().toString());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }
}
