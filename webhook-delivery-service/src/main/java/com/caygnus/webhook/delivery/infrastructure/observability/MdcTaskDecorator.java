package com.caygnus.webhook.delivery.infrastructure.observability;

import com.caygnus.webhook.delivery.application.DeliveryTask;
import com.caygnus.webhook.delivery.application.WorkerIdentity;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * Gives every worker thread the logging context of the delivery it is running.
 *
 * <p>The counterpart to {@code MdcRequestFilter} on the other side of the executor. Work crosses
 * a thread boundary here, and MDC is thread-local, so without this the most interesting log lines
 * in the system -- the ones describing an actual attempt -- would arrive with no way to tie them
 * to an event, a delivery, or the instance that made them.
 *
 * <p>Wrapping at the decorator means no class in the delivery path touches the MDC. The
 * alternative is a {@code put} and a {@code finally remove} in the executor, the HTTP client and
 * the outcome recorder, three chances to get the cleanup wrong on a pooled thread.
 */
@Component
public class MdcTaskDecorator implements TaskDecorator {

    private final WorkerIdentity worker;

    MdcTaskDecorator(WorkerIdentity worker) {
        this.worker = worker;
    }

    @Override
    public Runnable decorate(Runnable task) {
        if (!(task instanceof DeliveryTask deliveryTask)) {
            return task;
        }
        return () -> {
            MDC.put("workerId", worker.value());
            MDC.put("deliveryId", deliveryTask.delivery().deliveryId().toString());
            MDC.put("eventId", deliveryTask.delivery().eventId());
            MDC.put("attemptNumber", String.valueOf(deliveryTask.delivery().attemptNumber()));
            try {
                task.run();
            } finally {
                // Pooled threads outlive the task. A key left behind attaches itself to the next
                // delivery this thread picks up and quietly mislabels it.
                MDC.clear();
            }
        };
    }
}
