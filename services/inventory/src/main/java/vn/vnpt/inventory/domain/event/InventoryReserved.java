package vn.vnpt.inventory.domain.event;

import java.time.Instant;

/**
 * Hand-written reservation-created event — Story 1.6 placeholder.
 *
 * <p>Future Story 1.8 (Avro lifecycle events, FR-11) replaces this hand-written record with the
 * Avro-generated type at the same FQN.
 *
 * @param reservationUuid Snowflake id of the {@code inventory_reservation} row
 * @param variantId Snowflake id of the variant (cross-service reference to catalog)
 * @param warehouseId Snowflake id of the warehouse
 * @param quantity reserved units
 * @param sagaStepId ADR-11 idempotency key
 * @param orderUuid optional order FK (cross-service)
 * @param eventId Snowflake id of the outbox row (the idempotency key)
 * @param expiresAt reservation expiry timestamp
 * @param occurredAt event timestamp
 */
public record InventoryReserved(
    Long reservationUuid,
    Long variantId,
    Long warehouseId,
    long quantity,
    String sagaStepId,
    Long orderUuid,
    Long eventId,
    Instant expiresAt,
    Instant occurredAt) {}