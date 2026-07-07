package vn.vnpt.checkout.domain.exception;

import vn.vnpt.checkout.domain.OrderStatus;

/**
 * Thrown by {@code Order.transitionTo} when the requested edge is not in the FSM allowed matrix.
 *
 * <p>Story 2.5 / FR-22 / FR-23 (ADR-12). The exception carries {@code fromState} / {@code toState} /
 * {@code sagaStep} for the {@code OrderControllerExceptionHandler} response.
 */
public class OrderIllegalStateTransitionException extends RuntimeException {

  private final OrderStatus fromState;
  private final OrderStatus toState;
  private final String sagaStep;

  public OrderIllegalStateTransitionException(OrderStatus fromState, OrderStatus toState,
      String sagaStep, String message) {
    super(message + " (from=" + fromState + ", to=" + toState + ", step=" + sagaStep + ")");
    this.fromState = fromState;
    this.toState = toState;
    this.sagaStep = sagaStep;
  }

  public OrderStatus getFromState() {
    return fromState;
  }

  public OrderStatus getToState() {
    return toState;
  }

  public String getSagaStep() {
    return sagaStep;
  }
}