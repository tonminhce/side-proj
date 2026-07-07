package vn.vnpt.payment.application.port;

/**
 * Outbound payment port — Story 3.1 / FR-25 (ADR-11). The use case computes the stable idempotency
 * key via {@link vn.vnpt.payment.infrastructure.IdempotencyKey} and passes it on
 * {@link AuthorizePaymentCommand#idempotencyKey()}; the adapter forwards it to Stripe unchanged.
 *
 * <p>Replaces ad-hoc id generation (Story 2.4 footgun) — the key is the use case's responsibility
 * so the port cannot accidentally substitute a different one.
 */
public interface PaymentPort {

  PaymentResult authorize(AuthorizePaymentCommand cmd) throws PaymentPortUnavailableException;
}