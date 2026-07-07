package vn.vnpt.checkout.domain.exception;

import vn.vnpt.checkout.domain.Checkout;

/**
 * Raised when an optimistic-concurrency conflict occurs on a checkout mutation (Story 2.3, FR-21
 * pattern mirrors cart's FR-16).
 *
 * <p>Use cases catch {@code ObjectOptimisticLockingFailureException} and rethrow this, carrying the
 * latest persisted checkout so the controller can return HTTP 409 with the server's view for the BFF's
 * optimistic-update reconciliation. The saga (Story 2.5) is the primary consumer of this exception.
 */
public class CheckoutVersionConflictException extends RuntimeException {

  private final Long expectedVersion;
  private final Long actualVersion;
  private final transient Checkout latestCheckout;

  public CheckoutVersionConflictException(
      Long expectedVersion, Long actualVersion, Checkout latestCheckout) {
    super(
        "Checkout version conflict: expected="
            + expectedVersion
            + " actual="
            + actualVersion);
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
    this.latestCheckout = latestCheckout;
  }

  public Long getExpectedVersion() {
    return expectedVersion;
  }

  public Long getActualVersion() {
    return actualVersion;
  }

  public Checkout getLatestCheckout() {
    return latestCheckout;
  }
}