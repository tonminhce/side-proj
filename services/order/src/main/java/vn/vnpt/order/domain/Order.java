package vn.vnpt.order.domain;

import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;

/**
 * Order aggregate root — Story 4.1. Immutable value object: current state is a projection
 * over the {@code order_state_transition} log (FR-30: "current state is a projection").
 */
public record Order(
    long orderUuid,
    OrderState state,
    OrderPriceSnapshot priceSnapshot,
    long version) {
}