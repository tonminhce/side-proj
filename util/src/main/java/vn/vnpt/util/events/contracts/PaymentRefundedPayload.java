package vn.vnpt.util.events.contracts;

/**
 * Wire-format payload for {@code payment.refunded} events — payment → order bridge (FR-32).
 *
 * <p>Field shape mirrors {@code services/payment/.../application/event/PaymentRefundedEvent} and
 * {@code services/order/.../application/saga/event/PaymentRefundedEvent} exactly so Jackson
 * round-trips losslessly. {@code occurredAt} is serialized as ISO-8601 string (RFC 3339) on the
 * wire; consumers reconstruct {@link java.time.LocalDateTime} via
 * {@code LocalDateTime.parse(occurredAt)}.
 *
 * <p>Note: when the producer cannot resolve {@code orderUuid} from the Stripe charge metadata
 * (the metadata may be missing for the refund event since the original PaymentIntent metadata is
 * not always propagated), the producer publishes {@code orderUuid=0} as a sentinel and the
 * consumer-side saga looks up the order by {@code paymentIntentId} via the existing
 * {@code OrderStateTransitionRepository.findFirstByPaymentIntentIdOrderByIdDesc(...)} method
 * (not yet shipped; the saga falls back to the @{{@code 0L}} sentinel and skips — see Story 3.5
 * follow-up #4 resolution log).
 */
public record PaymentRefundedPayload(
    long orderUuid,
    String paymentIntentId,
    long amountCents,
    String currency,
    String occurredAt) {
}
