package com.caygnus.webhook.ingest.api;

import com.caygnus.webhook.common.api.ProblemCodes;
import com.caygnus.webhook.ingest.application.IngestEventUseCase;
import com.caygnus.webhook.ingest.domain.InvalidEventException;
import com.caygnus.webhook.ingest.domain.PayloadMismatchException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error this service returns, in one place, as RFC 7807 {@code application/problem+json}.
 *
 * <p>The distinction that matters most here is between 4xx and 5xx, because the caller is expected
 * to retry on one and not the other. A payload mismatch is the caller's to fix and will fail
 * identically forever; a database that is briefly unreachable is ours, and nothing has been lost
 * by the time they try again.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, "Validation failed",
                "The request body is not a valid event.");
        problem.setProperty("errors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail handleEventNotFound(EventNotFoundException exception) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, ProblemCodes.EVENT_NOT_FOUND,
                "Event not found", exception.getMessage());
        problem.setProperty("eventId", exception.eventId());
        return problem;
    }

    /** The checks that needed the clock or the serialised size, reported in the same shape. */
    @ExceptionHandler(InvalidEventException.class)
    ProblemDetail handleInvalidEvent(InvalidEventException exception) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, "Validation failed",
                "The request body is not a valid event.");
        problem.setProperty("errors", Map.of(exception.field(), exception.problem()));
        return problem;
    }

    /**
     * The same identifier, a different event. 409 rather than 200, because the two submissions
     * cannot both be right and guessing which one the caller meant could deliver the wrong body.
     */
    @ExceptionHandler(PayloadMismatchException.class)
    ProblemDetail handlePayloadMismatch(PayloadMismatchException exception) {
        log.warn("Rejected a payload mismatch for eventId={}", exception.eventId());
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT, ProblemCodes.PAYLOAD_MISMATCH,
                "Payload mismatch", exception.getMessage());
        problem.setProperty("eventId", exception.eventId());
        return problem;
    }

    /** See {@link IngestEventUseCase.ConcurrentIngestException}: rare, and safe to retry at once. */
    @ExceptionHandler(IngestEventUseCase.ConcurrentIngestException.class)
    ProblemDetail handleConcurrentIngest(IngestEventUseCase.ConcurrentIngestException exception) {
        log.warn("Ingest raced with a rolled-back transaction: {}", exception.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemCodes.CONCURRENT_CREATION,
                "Ingest raced", "The request can be retried immediately.");
    }

    /**
     * The database is unreachable.
     *
     * <p>503 and not 500, deliberately. Nothing was accepted, so nothing was lost, and the honest
     * answer to the caller is "not now" rather than "something went wrong" -- the first invites
     * the retry that will succeed.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, CannotAcquireLockException.class})
    ProblemDetail handleDatabaseUnavailable(Exception exception) {
        log.error("Could not reach the database; rejecting the submission", exception);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ProblemCodes.STORAGE_UNAVAILABLE,
                "Storage unavailable",
                "The event was not accepted and nothing was stored. The request can be retried.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCodes.INTERNAL_ERROR,
                "Internal error", "The request could not be completed.");
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }
}
