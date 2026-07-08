package vn.vnpt.order.domain.exception;

/** Thrown when the saga expects an order's immutable price snapshot to exist but it doesn't. */
public class OrderSnapshotMissingException extends RuntimeException {
  public OrderSnapshotMissingException(long orderUuid) {
    super("Order price snapshot missing for orderUuid=" + orderUuid
        + " (FR-31 immutability requires a captured-at-placement snapshot)");
  }
}