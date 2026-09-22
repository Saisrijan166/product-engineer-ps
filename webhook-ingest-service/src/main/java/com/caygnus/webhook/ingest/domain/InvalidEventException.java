package com.caygnus.webhook.ingest.domain;

/**
 * A request that parsed and passed its annotations but still is not a plausible event.
 *
 * <p>Separate from bean validation because these checks need something the annotations cannot see
 * -- the injected clock, or the serialised size of the payload rather than its shape.
 */
public class InvalidEventException extends RuntimeException {

    private final transient String field;
    private final transient String problem;

    public InvalidEventException(String field, String problem) {
        super("%s %s".formatted(field, problem));
        this.field = field;
        this.problem = problem;
    }

    public String field() {
        return field;
    }

    public String problem() {
        return problem;
    }
}
