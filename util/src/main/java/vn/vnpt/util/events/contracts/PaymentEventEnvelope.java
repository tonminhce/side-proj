package vn.vnpt.util.events.contracts;

import java.util.Map;

/**
 * Wire-format envelope for cross-service payment events — payment → order bridge (Story 4.1
 * follow-up, FR-32, ADR-20). This is the JSON shape published to the {@code payment.events} Kafka
 * topic and consumed by {@code services/order}.
 *
 * <p>The envelope fields mirror the HMAC envelope keys produced by
 * {@link vn.vnpt.util.events.JcsCanonicalJson} on the producer side and verified by
 * {@code services/order/.../security/OrderHmacEventVerifier} on the consumer side. The
 * {@code payload} string is the verbatim JSON of {@code PaymentCapturedEvent} or
 * {@code PaymentRefundedEvent} (the in-process record Jackson-serialized by
 * {@code ModulithOutboxPublisher.serialize}).
 *
 * <p>The {@code signatures} map carries the producer's HMAC envelope metadata
 * ({@code {service, hmac_sha256, key_id}}). It is treated as opaque on the wire — the consumer
 * rebuilds the JCS envelope from the other fields and verifies against {@code hmac_sha256} via
 * the shared secret loaded from the producer's Vault path.
 */
public record PaymentEventEnvelope(
    long eventId,
    String aggregateType,
    long aggregateId,
    String eventType,
    String payload,
    Map<String, String> signatures) {
}
