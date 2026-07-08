package vn.vnpt.order.domain.exception;

import vn.vnpt.order.domain.OrderState;

/** Thrown when an amend/cancel request targets a terminal state (CANCELLED, SHIPPED, DELIVERED). */
public class OrderTerminalStateException extends RuntimeException {
  public OrderTerminalStateException(long orderUuid, OrderState currentState) {
    super("Order " + orderUuid + " is in terminal state " + currentState + "; no further edits allowed");
  }
}