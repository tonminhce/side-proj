package vn.vnpt.payment.application.event;

import java.time.LocalDateTime;

/**
 * Cross-module event emitted when Stripe confirms a refund — Story 3.5 follow-up / FR-28.
 *
 * <p>Field shape mirrors {@link PaymentCapturedEvent} so a future
 * {@code PaymentRefundedOrderAdvancer} can consume it with a near-identical handler. Today the
 * order service has no {@code payment.refunded} consumer; the event is published to the outbox
 * for downstream services (admin reporting, ledger reconciliation) to pick up.
 *
 * <p>For refunds the Stripe webhook is {@code charge.refunded}; the
 * {@code data.object.payment_intent} field carries the original {@code paymentIntentId}.
 */
public record PaymentRefundedEvent(
    long orderUuid,
    String paymentIntentId,
    long amountCents,
    String currency,
    LocalDateTime occurredAt) {
}