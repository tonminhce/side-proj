package vn.vnpt.checkout.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * {@code order.created} event payload — Story 2.5 / FR-22 (ADR-12 / ADR-20).
 *
 * <p>Emitted by {@code OrderSagaOrchestrator} on the {@code (none) → CREATED} transition. Serialized
 * to the {@code outbox.payload} JSONB column; the {@code signatures} map carries the ADR-20 producer
 * HMAC ({@code {"hmac_sha256": "<base64url>"}}).
 *
 * <p>Envelope shape ({@code eventId}, {@code aggregateType}, {@code aggregateId}, {@code occurredAt},
 * {@code signatures}) mirrors {@code CheckoutStartedEvent} and {@code InventoryLifecycleEvent} —
 * do not deviate.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderCreatedEvent {

  /** Snowflake id of the outbox row — the idempotency key for downstream consumers. */
  Long eventId;

  /** Aggregate root name ({@code "Order"}). */
  String aggregateType;

  /** Snowflake id of the order. */
  Long aggregateId;

  /** Event timestamp (UTC). */
  Instant occurredAt;

  Long orderUuid;
  Long cartUuid;
  Long checkoutUuid;
  /** Denormalized copy from {@code Checkout.paymentIntentId} (R-15 / ADR-23 — non-secret). */
  String paymentIntentId;
  String tenantId;

  /** ADR-20 producer HMAC map. */
  Map<String, String> signatures;
}