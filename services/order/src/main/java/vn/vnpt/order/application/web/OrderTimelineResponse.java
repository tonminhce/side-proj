package vn.vnpt.order.application.web;

import java.util.List;

/** Timeline response shape — Story 4.3 / FR-33. */
public record OrderTimelineResponse(long orderUuid, List<OrderTimelineEntry> timeline) {
}