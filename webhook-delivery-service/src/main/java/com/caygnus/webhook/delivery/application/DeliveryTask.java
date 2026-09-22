package com.caygnus.webhook.delivery.application;

/**
 * One delivery attempt, as something the executor can hand to a worker.
 *
 * <p>A named type rather than a lambda so that the {@code MdcTaskDecorator} can see what the task
 * is <em>about</em> and set the logging context from it. With an anonymous lambda the decorator
 * would have nothing to inspect, and every class along the path would have to remember to
 * populate the MDC itself -- which is exactly the scattering ARCHITECTURE.md §11 rules out.
 */
public record DeliveryTask(ClaimedDelivery delivery, Runnable work) implements Runnable {

    @Override
    public void run() {
        work.run();
    }
}
