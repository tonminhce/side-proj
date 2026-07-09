package vn.vnpt.util.events.contracts;

/**
 * Wire-format payload for {@code payment.captured} events — payment → order bridge (FR-32).
 *
 * <p>Field shape mirrors {@code services/payment/.../application/event/PaymentCapturedEvent} and
 * {@code services/order/.../application/saga/event/PaymentCapturedEvent} exactly so Jackson
 * round-trips losslessly. {@code occurredAt} is serialized as ISO-8601 string (RFC 3339) on the
 * wire; consumers reconstruct {@link java.time.LocalDateTime} via
 * {@code LocalDateTime.parse(occurredAt)}.
 */
public record PaymentCapturedPayload(
    long orderUuid,
    String paymentIntentId,
    long amountCents,
    String currency,
    String occurredAt) {
}
