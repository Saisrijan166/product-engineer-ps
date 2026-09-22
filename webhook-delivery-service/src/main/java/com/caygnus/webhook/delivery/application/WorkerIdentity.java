package com.caygnus.webhook.delivery.application;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who this instance is, for lease ownership and attempt history.
 *
 * <p>The random suffix matters as much as the hostname: two instances on one host, or the same
 * pod restarted, must not share an identity, or a restarted process would look like the owner of
 * leases it no longer holds. Held to 64 characters to match the column.
 */
@Component
public class WorkerIdentity {

    private static final int MAX_LENGTH = 64;

    private final String value;

    WorkerIdentity() {
        this("%s:%s:%s".formatted(hostname(), processId(), UUID.randomUUID().toString().substring(0, 8)));
    }

    WorkerIdentity(String value) {
        this.value = value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }

    public String value() {
        return value;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // A host that cannot name itself is still a perfectly good worker.
            return "unknown-host";
        }
    }

    private static String processId() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }

    @Override
    public String toString() {
        return value;
    }
}
