package vn.vnpt.payment.application.event;

import java.time.LocalDateTime;

/**
 * Cross-module event emitted when Stripe confirms a successful capture — Story 3.5 follow-up / FR-28.
 *
 * <p>Field shape is intentionally identical to
 * {@code services/order/.../application/saga/event/PaymentCapturedEvent} so Spring Modulith's
 * intra-JVM listener dispatch resolves the right consumer class on the order side
 * ({@code PaymentCapturedOrderAdvancer.onPaymentCaptured}).
 *
 * <p>{@code orderUuid} is sourced from {@code data.object.metadata.order_uuid} in the Stripe
 * webhook payload (set by checkout when it created the PaymentIntent). {@code paymentIntentId}
 * is the Stripe-side identifier and is used as the outbox aggregate id
 * ({@code ModulithOutboxPublisher.append("Payment", piId, "payment.captured", ...)}).
 */
public record PaymentCapturedEvent(
    long orderUuid,
    String paymentIntentId,
    long amountCents,
    String currency,
    LocalDateTime occurredAt) {
}