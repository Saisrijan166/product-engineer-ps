package com.caygnus.webhook.ingest.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts request context into the MDC so every log line underneath carries it.
 *
 * <p>One filter, rather than a line of {@code MDC.put} at the top of each handler. Correlation is
 * an infrastructural concern: the moment it lives in business code it is one method that forgot
 * it away from being useless, and the log lines that matter most during an incident are usually
 * the ones from the path nobody remembered to annotate.
 *
 * <p>The identifier is taken from the URI rather than the body, because a filter that consumed
 * the request body would have to buffer and replay it. Reads carry theirs in the path, and the
 * one write that does not -- ingestion -- logs its own identifier once it has parsed it.
 *
 * <p>The {@code finally} is the whole point: MDC is thread-local and threads are pooled, so a
 * key left behind attaches itself to whatever request runs next and quietly lies.
 */
@Component
@Order(1)
public class MdcRequestFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID = "requestId";
    private static final String RESOURCE_ID = "eventId";

    /** Matches {@code .../events/{id}}, which is where both read endpoints carry theirs. */
    private static final String SEGMENT = "/events/";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        MDC.put(REQUEST_ID, UUID.randomUUID().toString());
        resourceIdFrom(request.getRequestURI()).ifPresent(id -> MDC.put(RESOURCE_ID, id));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
            MDC.remove(RESOURCE_ID);
        }
    }

    private static java.util.Optional<String> resourceIdFrom(String uri) {
        int start = uri.indexOf(SEGMENT);
        if (start < 0) {
            return java.util.Optional.empty();
        }
        String tail = uri.substring(start + SEGMENT.length());
        int end = tail.indexOf('/');
        String id = end < 0 ? tail : tail.substring(0, end);
        return id.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(id);
    }
}
