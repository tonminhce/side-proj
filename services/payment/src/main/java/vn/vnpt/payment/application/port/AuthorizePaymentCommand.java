package vn.vnpt.payment.application.port;

/**
 * Command carrying the inputs for {@link PaymentPort#authorize} — Story 3.1 / FR-25.
 *
 * <p>{@code idempotencyKey} is computed by the use case via
 * {@link vn.vnpt.payment.infrastructure.IdempotencyKey#forOrderStep(long, String)} and stamped on
 * the command via {@link #withIdempotencyKey(String)} before the port sees it; the port MUST NOT
 * regenerate it (that is the bug class Story 3.1 exists to prevent).
 */
public record AuthorizePaymentCommand(
    long orderUuid,
    long amountCents,
    String currency,
    String stripeCustomerId,
    String idempotencyKey) {

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
  }

  public AuthorizePaymentCommand withIdempotencyKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must be set by the use case");
    }
    return new AuthorizePaymentCommand(orderUuid, amountCents, currency, stripeCustomerId, key);
  }
}