package vn.vnpt.inventory.domain.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Unified inventory lifecycle event — Story 1.8 / FR-11.
 *
 * <p>Single Avro-compatible record carrying all five lifecycle phases:
 * {@link LifecyclePhase#RESERVED}, {@link LifecyclePhase#RELEASED},
 * {@link LifecyclePhase#ALLOCATED}, {@link LifecyclePhase#SHIPPED},
 * {@link LifecyclePhase#ADJUSTED}. Replaces the Story 1.6 split into
 * {@code InventoryReserved} / {@code InventoryReleased} records.
 *
 * <p>Design choice (one topic with discriminator vs N topics): one topic wins because
 * (a) all events belong to the same aggregate family, (b) ordering matters across phases
 * (a reservation's {@code RESERVED} must precede its {@code RELEASED} — same partition key
 * on a single topic guarantees this), (c) consumers want a single subscription to see all
 * phases. The {@code phase} enum + nullable phase-specific fields is the standard Kafka
 * Schema-Registry pattern; the 5-separate-topics alternative produces 2-3x more consumer
 * code with no observable benefit at v1 scale.
 *
 * <p>{@link JsonInclude.Include#NON_NULL} omits phase-specific fields when null — keeps the
 * wire shape compact and avoids Jackson's default null-field pollution. ADR-20 producer
 * HMAC signature is stored in {@link #signatures} (recomputed at the consumer side).
 *
 * <p>{@code @Value} + {@code @Builder} + {@code @Jacksonized} provide an immutable record
 * with a builder factory (Lombok 1.18+). The {@code @Jacksonized} annotation routes Jackson
 * through the builder constructor — required for Boot 4's Jackson 3 default which needs
 * setters absent on records.
 *
 * <p>Wire topic: {@code inventory.lifecycle}. Legacy topics {@code inventory.reserved} +
 * {@code inventory.released} remain live as dual-publish aliases until the Sprint 9
 * migration story cuts them.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InventoryLifecycleEvent {

  /** Snowflake id of the outbox row — the idempotency key for downstream consumers. */
  Long eventId;

  /** Aggregate root name ({@code "InventoryReservation"} or {@code "InventoryLedger"}). */
  String aggregateType;

  /** Snowflake id of the aggregate root (reservationUuid or ledgerEntryUuid). */
  Long aggregateId;

  /** Event timestamp (UTC). */
  Instant occurredAt;

  /** Phase discriminator — see {@link LifecyclePhase}. */
  LifecyclePhase phase;

  /** Present for RESERVED/RELEASED/ALLOCATED phases. */
  Long reservationUuid;

  Long variantId;
  Long warehouseId;
  Long quantity;

  /** Lowercase reason string — the {@link vn.vnpt.inventory.domain.InventoryReason#toColumnValue()}. */
  String reason;

  /** ADR-11 idempotency key. Present for saga-driven phases. */
  String sagaStepId;

  /** Present when the saga has bound an order. */
  Long orderUuid;

  String tenantId;

  /** ADR-20 producer HMAC map ({@code {"hmac_sha256": "<base64url>"}}). */
  Map<String, String> signatures;
}