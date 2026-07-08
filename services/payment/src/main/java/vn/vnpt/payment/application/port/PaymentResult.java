package vn.vnpt.payment.application.port;

/**
 * Result of a {@link PaymentPort#authorize} call — Story 3.1 / FR-25,
 * Story 3.5 follow-up / FR-27 (3DS).
 *
 * <p>{@link #requiresActionUrl} is non-null only when {@link Status#REQUIRES_ACTION} — it's the
 * 3DS challenge URL extracted from
 * {@code PaymentIntent.getNextAction().getRedirectToUrl().getUrl()} (stripe-java 28.x). The BFF
 * redirects the browser to this URL so Stripe can present the challenge iframe; the saga waits
 * on the subsequent {@code payment.captured} webhook event before advancing
 * {@code OrderState.PLACED -> PAID}.
 */
public record PaymentResult(long paymentIntentId, Status status, String requiresActionUrl) {

  /** Backward-compat ctor for tests + non-3DS callers — {@code requiresActionUrl=null}. */
  public PaymentResult(long paymentIntentId, Status status) {
    this(paymentIntentId, status, null);
  }

  public enum Status {
    /** Stripe returned {@code requires_action} — card needs 3DS step-up (Story 3.5). */
    REQUIRES_ACTION,
    /** Stripe returned {@code succeeded} — authorization captured server-side. */
    SUCCEEDED,
    /** Stripe returned {@code requires_confirmation} — server-side confirm call is needed. */
    REQUIRES_CONFIRMATION
  }
}