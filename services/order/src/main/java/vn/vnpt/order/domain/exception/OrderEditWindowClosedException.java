package vn.vnpt.order.domain.exception;

import java.time.Instant;

/** Thrown when an amend/cancel request arrives after the 30-minute window. */
public class OrderEditWindowClosedException extends RuntimeException {
  public OrderEditWindowClosedException(long orderUuid, Instant closesAt) {
    super("Edit window closed for orderUuid=" + orderUuid + "; closesAt=" + closesAt);
  }
}