package vn.vnpt.order.application.port;

import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;

/**
 * Command for appending a state transition to an order — Story 4.1 / FR-30, FR-31.
 * Trust-boundary validation lives in the compact constructor.
 */
public record AppendOrderTransitionCommand(
    long orderUuid,
    OrderState toState,
    String sagaStep,
    OrderPriceSnapshot priceSnapshot) {

  public AppendOrderTransitionCommand {
    if (toState == null) {
      throw new IllegalArgumentException("toState must not be null");
    }
    if (sagaStep == null || sagaStep.isBlank()) {
      throw new IllegalArgumentException("sagaStep must not be null or blank");
    }
    if (priceSnapshot == null) {
      throw new IllegalArgumentException("priceSnapshot must not be null (FR-31 captures at order.placed)");
    }
  }
}