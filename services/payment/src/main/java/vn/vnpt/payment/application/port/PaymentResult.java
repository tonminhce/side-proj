package vn.vnpt.payment.application.port;

/** Result of a successful {@link PaymentPort#authorize} call — Story 3.1 / FR-25. */
public record PaymentResult(long paymentIntentId, Status status) {

  public enum Status {
    /** Stripe returned {@code requires_action} — card needs 3DS step-up (Story 3.5). */
    REQUIRES_ACTION,
    /** Stripe returned {@code succeeded} — authorization captured server-side. */
    SUCCEEDED,
    /** Stripe returned {@code requires_confirmation} — server-side confirm call is needed. */
    REQUIRES_CONFIRMATION
  }
}