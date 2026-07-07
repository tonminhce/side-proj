package vn.vnpt.checkout.application.port;

/**
 * Stripe PaymentIntent gateway port — Story 2.4 / FR-20 (CheckoutService owns the create lifecycle).
 *
 * <p>The adapter ({@code vn.vnpt.checkout.infrastructure.stripe.StripePaymentIntentGateway}) talks to
 * the Stripe SDK; the use case depends only on this port, mirroring the {@link OutboxPublisher}
 * pattern.
 */
public interface StripePaymentGateway {

  /**
   * Result of a successful PaymentIntent creation. {@code paymentIntentId} is non-secret and may be
   * logged; {@code clientSecret} is secret and MUST NOT be logged (R-15 / ADR-23 / NFR-OBS-5).
   */
  record Result(String paymentIntentId, String clientSecret) {}

  /**
   * Create a Stripe PaymentIntent with manual capture (FR-20 — two-step confirm→capture flow; the
   * saga captures after {@code payment_intent.succeeded} in Story 2.5).
   *
   * @param amountMinor Long minor units (đồng for VND). Never negative.
   * @param currency non-blank ISO 4217 code (e.g. {@code "VND"}).
   * @param idempotencyKey stable key derived from {@code (checkoutUuid, "stripe.payment_intent.create")}
   *     per ADR-11 / NFR-IDEM-2 — retries reuse the same PaymentIntent.
   */
  Result createPaymentIntent(long amountMinor, String currency, String idempotencyKey);
}