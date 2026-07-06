package vn.vnpt.inventory.domain.event;

import java.time.Instant;

/**
 * Hand-written reservation-released event — Story 1.6 placeholder.
 *
 * <p>Emitted both by saga-initiated release (cancel) and sweeper-initiated release (TTL
 * expiry). Same code path — both branches pass through {@code ReleaseInventoryUseCase} which
 * emits this event.
 *
 * @param reservationUuid Snowflake id of the {@code inventory_reservation} row
 * @param variantId Snowflake id of the variant (cross-service reference to catalog)
 * @param warehouseId Snowflake id of the warehouse
 * @param quantity released units (mirror of the reservation's negative delta)
 * @param sagaStepId ADR-11 idempotency key
 * @param orderUuid optional order FK (cross-service)
 * @param eventId Snowflake id of the outbox row (the idempotency key)
 * @param releasedAt release timestamp
 */
public record InventoryReleased(
    Long reservationUuid,
    Long variantId,
    Long warehouseId,
    long quantity,
    String sagaStepId,
    Long orderUuid,
    Long eventId,
    Instant releasedAt) {}