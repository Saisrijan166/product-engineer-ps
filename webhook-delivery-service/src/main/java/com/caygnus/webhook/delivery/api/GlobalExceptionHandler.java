package com.caygnus.webhook.delivery.api;

import com.caygnus.webhook.common.api.ProblemCodes;
import com.caygnus.webhook.delivery.application.CreateDeliveryUseCase;
import com.caygnus.webhook.delivery.domain.IllegalStateTransitionException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error this service returns, in one place, as RFC 7807 {@code application/problem+json}.
 *
 * <p>Having a single advice is the point: a caller can rely on one shape for every failure, and a
 * reader can see the whole error contract without going hunting. Each mapping below is a decision
 * about whether the caller did something wrong, whether retrying would help, and how much to say --
 * a stack trace is a gift to an attacker and no help at all to the ingest service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Bad request body. The field errors are listed because the caller is another service of ours,
     * and "which field" is the only thing that makes this actionable.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> fieldErrors.put(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST, ProblemCodes.VALIDATION_FAILED, "Validation failed",
                "The request body is not a valid delivery request.");
        problem.setProperty("errors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DeliveryNotFoundException.class)
    ProblemDetail handleNotFound(DeliveryNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, ProblemCodes.DELIVERY_NOT_FOUND,
                "Delivery not found", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    ProblemDetail handleIdempotencyKeyMismatch(IdempotencyKeyMismatchException exception) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCodes.IDEMPOTENCY_KEY_MISMATCH,
                "Idempotency key mismatch", exception.getMessage());
    }

    /** An unparseable UUID or an unknown status in the query string. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handleBadParameter(MethodArgumentTypeMismatchException exception) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCodes.INVALID_QUERY,
                "Invalid parameter",
                "'%s' is not a valid value for '%s'.".formatted(exception.getValue(), exception.getName()));
    }

    /**
     * Another writer got there first -- typically the reaper and a returning worker meeting on one
     * row. 409 and not 500: nothing is broken, and repeating the request is the right response.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleConcurrentModification(ObjectOptimisticLockingFailureException exception) {
        log.warn("Concurrent modification of a delivery: {}", exception.getMessage());
        return problem(HttpStatus.CONFLICT, ProblemCodes.CONCURRENT_MODIFICATION,
                "Concurrent modification",
                "This delivery was changed by another writer. The request can be retried.");
    }

    /**
     * Something asked a delivery to do what its state machine forbids -- a terminal delivery being
     * claimed, say. That is a bug rather than a caller error, so it is logged loudly even though the
     * response stays terse.
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    ProblemDetail handleIllegalTransition(IllegalStateTransitionException exception) {
        log.error("Illegal delivery state transition", exception);
        return problem(HttpStatus.CONFLICT, ProblemCodes.ILLEGAL_STATE_TRANSITION,
                "Illegal state transition", exception.getMessage());
    }

    /** See {@link CreateDeliveryUseCase.ConcurrentCreationException}: rare, and safe to retry. */
    @ExceptionHandler(CreateDeliveryUseCase.ConcurrentCreationException.class)
    ProblemDetail handleConcurrentCreation(CreateDeliveryUseCase.ConcurrentCreationException exception) {
        log.warn("Delivery creation raced with a rolled-back transaction: {}", exception.getMessage());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemCodes.CONCURRENT_CREATION,
                "Delivery creation raced", "The request can be retried.");
    }

    /**
     * The catch-all. The detail is deliberately generic: whatever went wrong belongs in the logs,
     * not in a response body that leaves the process.
     */
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

    /** Keeps Spring's own {@link ErrorResponseException} shapes consistent with the ones above. */
    @Override
    protected ProblemDetail createProblemDetail(
            Exception exception, HttpStatusCode status, String defaultDetail,
            String detailMessageCode, Object[] detailMessageArguments, WebRequest request) {

        ProblemDetail problem = super.createProblemDetail(
                exception, status, defaultDetail, detailMessageCode, detailMessageArguments, request);
        if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
            problem.setType(ProblemCodes.INTERNAL_ERROR);
        }
        return problem;
    }
}
