package vn.vnpt.checkout.domain.exception;

/**
 * Thrown by {@code OrderController} / {@code GetOrderByCheckoutUseCase} when no {@code Order} row
 * exists for the given checkout uuid.
 *
 * <p>Story 2.5 / FR-22 — surfaces as a 404 via {@code CheckoutControllerExceptionHandler}.
 */
public class OrderNotFoundException extends RuntimeException {

  private final Long checkoutUuid;

  public OrderNotFoundException(Long checkoutUuid) {
    super("Order not found for checkoutUuid=" + checkoutUuid);
    this.checkoutUuid = checkoutUuid;
  }

  public Long getCheckoutUuid() {
    return checkoutUuid;
  }
}