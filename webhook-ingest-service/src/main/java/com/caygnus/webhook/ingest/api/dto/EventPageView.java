package com.caygnus.webhook.ingest.api.dto;

import java.util.List;

/** A page of accepted events, newest first. */
public record EventPageView(List<EventSummaryView> events, int page, int size, long totalEvents) {
}
