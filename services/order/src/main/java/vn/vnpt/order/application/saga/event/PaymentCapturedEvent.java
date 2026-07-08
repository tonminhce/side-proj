package vn.vnpt.order.application.saga.event;

import java.time.LocalDateTime;

/**
 * Cross-module event record for {@code payment.captured} — Story 4.2. The payment service emits
 * a compatible record (per FR-28); the order service consumes this shape via
 * {@code @ApplicationModuleListener}. The producer-side event is in
 * {@code services/payment/.../domain/event/} (verify the field names align; the saga's seam
 * is this record).
 */
public record PaymentCapturedEvent(
    long orderUuid,
    String paymentIntentId,
    long amountCents,
    String currency,
    LocalDateTime occurredAt) {
}