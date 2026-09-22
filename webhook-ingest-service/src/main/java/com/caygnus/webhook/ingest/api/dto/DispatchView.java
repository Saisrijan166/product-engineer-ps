package com.caygnus.webhook.ingest.api.dto;

import com.caygnus.webhook.ingest.domain.DispatchStatus;
import com.caygnus.webhook.ingest.domain.OutboxDispatch;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How the handoff to the delivery service is going.
 *
 * <p>Usually uninteresting -- {@code DISPATCHED} after the first tick -- and occasionally the only
 * thing that explains the response. An event whose {@code delivery} is null and whose dispatch is
 * {@code FAILED} has not been forgotten; it is stuck at a step the reader can now see.
 *
 * @param lastError present only when something went wrong, which is the only time it means anything
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DispatchView(DispatchStatus status, int attempts, String lastError) {

    public static DispatchView of(OutboxDispatch dispatch) {
        return new DispatchView(dispatch.getStatus(), dispatch.getAttempts(), dispatch.getLastError());
    }
}
