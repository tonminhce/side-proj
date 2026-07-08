package vn.vnpt.order.domain.exception;

/** Thrown when the optimistic-concurrency version (transition count) does not match. */
public class OrderVersionMismatchException extends RuntimeException {
  public OrderVersionMismatchException(long orderUuid, long expected, long current) {
    super("Version mismatch for orderUuid=" + orderUuid
        + "; expected=" + expected + " current=" + current);
  }
}