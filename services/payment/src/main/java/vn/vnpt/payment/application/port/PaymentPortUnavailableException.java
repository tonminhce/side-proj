package vn.vnpt.payment.application.port;

/** Thrown on transient Stripe API failures (5xx, network timeout) — Story 3.1 / FR-25. */
public class PaymentPortUnavailableException extends RuntimeException {

  public PaymentPortUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public PaymentPortUnavailableException(String message) {
    super(message);
  }
}