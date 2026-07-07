package vn.vnpt.cart.domain.exception;

/**
 * Raised by {@code GetOrCreateCartUseCase.findByUuid(...)} when an explicit cart lookup misses
 * (Story 2.1). Maps to HTTP 404. The {@code getOrCreate} path never throws this.
 */
public class CartNotFoundException extends RuntimeException {

  private final Long cartUuid;

  public CartNotFoundException(Long cartUuid) {
    super("Cart not found: cartUuid=" + cartUuid);
    this.cartUuid = cartUuid;
  }

  public Long getCartUuid() {
    return cartUuid;
  }
}
