package vn.vnpt.payment.application.port;

/**
 * Command carrying the inputs for {@link PaymentPort#authorize} — Story 3.1 / FR-25,
 * Story 3.5 follow-up / FR-27 (3DS).
 *
 * <p>{@code idempotencyKey} is computed by the use case via
 * {@link vn.vnpt.payment.infrastructure.IdempotencyKey#forOrderStep(long, String)} and stamped on
 * the command via {@link #withIdempotencyKey(String)} before the port sees it; the port MUST NOT
 * regenerate it (that is the bug class Story 3.1 exists to prevent).
 *
 * <p>{@code country} (ISO-3166-1 alpha-2) and {@code riskLevel} drive the
 * {@link ThreeDSecureDecision} — both nullable; null means "no merchant-side 3DS override"
 * and Stripe's automatic SCA engine picks the policy. When set, they enable an explicit
 * {@code request_three_d_secure=ANY} on the PaymentIntent (Story 3.5 follow-up / FR-27).
 */
public record AuthorizePaymentCommand(
    long orderUuid,
    long amountCents,
    String currency,
    String stripeCustomerId,
    String idempotencyKey,
    String country,
    RiskLevel riskLevel) {

  public AuthorizePaymentCommand {
    if (currency == null || !currency.matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("currency must be an uppercase 3-letter ISO-4217 code");
    }
    if (amountCents < 0) {
      // Trust-boundary validation: a negative amount is a client error (4xx-shaped), not a
      // transient port failure. Throwing IAE here keeps the saga compensator from retrying
      // a non-retryable condition; the port stays a dumb pass-through (no validation).
      throw new IllegalArgumentException("amountCents must be non-negative (got " + amountCents + ")");
    }
    if (country != null && !country.matches("[A-Za-z]{2}")) {
      throw new IllegalArgumentException("country must be a 2-letter ISO-3166-1 alpha-2 code (got: " + country + ")");
    }
  }

  /** Backward-compat ctor for tests + callers that don't pass 3DS context (Story 3.1 / pre-3.5). */
  public AuthorizePaymentCommand(
      long orderUuid, long amountCents, String currency, String stripeCustomerId, String idempotencyKey) {
    this(orderUuid, amountCents, currency, stripeCustomerId, idempotencyKey, null, null);
  }

  public AuthorizePaymentCommand withIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must be set by the use case");
    }
    return new AuthorizePaymentCommand(orderUuid, amountCents, currency, stripeCustomerId, key, country, riskLevel);
  }

  /** Story 3.5 follow-up: attach a 2-letter ISO country code for the 3DS decision. */
  public AuthorizePaymentCommand withCountry(String c) {
    return new AuthorizePaymentCommand(orderUuid, amountCents, currency, stripeCustomerId, idempotencyKey, c, riskLevel);
  }

  /** Story 3.5 follow-up: attach a risk classification for the 3DS decision. */
  public AuthorizePaymentCommand withRiskLevel(RiskLevel r) {
    return new AuthorizePaymentCommand(orderUuid, amountCents, currency, stripeCustomerId, idempotencyKey, country, r);
  }
}