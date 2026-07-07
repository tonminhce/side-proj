package vn.vnpt.cart.domain.exception;

import vn.vnpt.cart.domain.Cart;

/**
 * Raised when an optimistic-concurrency conflict occurs on a cart mutation (Story 2.1 / FR-16).
 *
 * <p>Use cases catch {@code ObjectOptimisticLockingFailureException} and rethrow this, carrying the
 * latest persisted cart so the controller can return HTTP 409 with the server's view for the BFF's
 * optimistic-update reconciliation.
 */
public class CartVersionConflictException extends RuntimeException {

  private final Long expectedVersion;
  private final Long actualVersion;
  private final transient Cart latestCart;

  public CartVersionConflictException(Long expectedVersion, Long actualVersion, Cart latestCart) {
    super("Cart version conflict: expected=" + expectedVersion + " actual=" + actualVersion);
    this.expectedVersion = expectedVersion;
    this.actualVersion = actualVersion;
    this.latestCart = latestCart;
  }

  public Long getExpectedVersion() {
    return expectedVersion;
  }

  public Long getActualVersion() {
    return actualVersion;
  }

  public Cart getLatestCart() {
    return latestCart;
  }
}
