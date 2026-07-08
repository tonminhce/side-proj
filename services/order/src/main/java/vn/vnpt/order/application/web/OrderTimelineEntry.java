package vn.vnpt.order.application.web;

import java.time.Instant;

/** Single timeline entry — Story 4.3 / FR-33. */
public record OrderTimelineEntry(String state, Instant timestamp) {
}