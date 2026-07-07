package vn.vnpt.cart.domain.exception;

/**
 * Raised when a line is not found in the given cart (Story 2.1). Maps to HTTP 404.
 */
public class CartLineNotFoundException extends RuntimeException {

  private final Long cartUuid;
  private final Long lineUuid;

  public CartLineNotFoundException(Long cartUuid, Long lineUuid) {
    super("Cart line not found: cartUuid=" + cartUuid + " lineUuid=" + lineUuid);
    this.cartUuid = cartUuid;
    this.lineUuid = lineUuid;
  }

  public Long getCartUuid() {
    return cartUuid;
  }

  public Long getLineUuid() {
    return lineUuid;
  }
}
