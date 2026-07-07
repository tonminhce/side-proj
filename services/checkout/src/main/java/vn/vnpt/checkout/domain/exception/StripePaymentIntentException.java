package vn.vnpt.checkout.domain.exception;

/**
 * Raised when the Stripe PaymentIntent create call fails (Story 2.4 / FR-20).
 *
 * <p>Wraps any {@code StripeException} or transport error from the gateway adapter so the controller
 * never leaks the raw Stripe body. Maps to a sanitized 502/503 via the exception handler.
 * architecture.md:532-535.
 */
public class StripePaymentIntentException extends RuntimeException {

  public StripePaymentIntentException(String message, Throwable cause) {
    super(message, cause);
  }

  public StripePaymentIntentException(String message) {
    super(message);
  }
}