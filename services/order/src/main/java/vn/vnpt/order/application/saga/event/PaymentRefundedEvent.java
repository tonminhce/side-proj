package vn.vnpt.order.application.saga.event;

import java.time.LocalDateTime;

/**
 * Cross-module event emitted when Stripe confirms a refund — Story 3.5 follow-up #4.
 * Field shape mirrors {@link PaymentCapturedEvent} so the saga listener can consume both
 * with a near-identical handler.
 *
 * <p>{@code orderUuid} is sourced from {@code data.object.metadata.order_uuid} on the
 * {@code charge.refunded} Stripe webhook payload. The merchant must set
 * {@code metadata.order_uuid} on the charge itself (Stripe does not propagate the parent
 * PaymentIntent's metadata automatically). When missing, the payment service skips the
 * publish and logs a warning (see {@code HandleStripeWebhookUseCase.TYPE_CHARGE_REFUNDED}).
 */
public record PaymentRefundedEvent(
    long orderUuid,
    String paymentIntentId,
    long amountCents,
    String currency,
    LocalDateTime occurredAt) {
}