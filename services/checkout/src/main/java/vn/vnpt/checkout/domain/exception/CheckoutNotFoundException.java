package vn.vnpt.checkout.domain.exception;

/**
 * Raised by {@code GetCheckoutUseCase.findByCheckoutUuid(...)} when an explicit checkout lookup
 * misses (Story 2.3 / FR-21). Maps to HTTP 404 via {@code CheckoutControllerExceptionHandler}.
 */
public class CheckoutNotFoundException extends RuntimeException {

  private final Long checkoutUuid;

  public CheckoutNotFoundException(Long checkoutUuid) {
    super("Checkout not found: checkoutUuid=" + checkoutUuid);
    this.checkoutUuid = checkoutUuid;
  }

  public Long getCheckoutUuid() {
    return checkoutUuid;
  }
}