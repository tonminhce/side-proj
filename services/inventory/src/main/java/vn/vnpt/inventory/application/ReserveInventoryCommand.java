package vn.vnpt.inventory.application;

import java.time.Duration;
import vn.vnpt.inventory.domain.Region;

/**
 * Command for {@link ReserveInventoryUseCase#reserve(ReserveInventoryCommand)}.
 *
 * <p>{@code ttl} is the reservation lifetime from now; defaults to {@code MAX_RESERVATION_TTL_MINUTES}
 * (yml {@code inventory.reservation.ttl-minutes}, default 15) if {@code null}.
 *
 * <p>Story 1.7 / FR-10 — exactly ONE of {@code warehouseId} / {@code shippingRegion} MUST be
 * non-null. The use case validates this and rejects both-null / both-non-null. The v1
 * saga-step path (Story 2.5) continues to supply {@code warehouseId}; new "fulfill from
 * nearest" callers supply {@code shippingRegion} for picker dispatch.
 *
 * @param variantId the variant's Snowflake id (cross-service reference to catalog)
 * @param warehouseId v1 contract — caller pre-picks the warehouse. Null when dispatching via {@code shippingRegion}.
 * @param shippingRegion FR-10 dispatch — when {@code warehouseId} is null, the picker selects
 *     an in-region warehouse (or cross-region fallback).
 * @param quantity reserved units; must be {@code > 0} (CHECK constraint enforces at DB)
 * @param sagaStepId ADR-11 idempotency key; must be non-null
 * @param orderUuid optional order FK (cross-service)
 * @param ttl reservation lifetime; must be positive (non-null, non-negative, non-zero)
 */
public record ReserveInventoryCommand(
    Long variantId,
    Long warehouseId,
    Region shippingRegion,
    long quantity,
    String sagaStepId,
    Long orderUuid,
    Duration ttl) {}