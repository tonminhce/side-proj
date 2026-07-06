package vn.vnpt.inventory.application;

import java.time.Duration;

/**
 * Command for {@link ReserveInventoryUseCase#reserve(ReserveInventoryCommand)}.
 *
 * <p>{@code ttl} is the reservation lifetime from now; defaults to {@code MAX_RESERVATION_TTL_MINUTES}
 * (yml {@code inventory.reservation.ttl-minutes}, default 15) if {@code null}.
 *
 * @param variantId the variant's Snowflake id (cross-service reference to catalog)
 * @param warehouseId the warehouse's Snowflake id
 * @param quantity reserved units; must be {@code > 0} (CHECK constraint enforces at DB)
 * @param sagaStepId ADR-11 idempotency key; must be non-null
 * @param orderUuid optional order FK (cross-service)
 * @param ttl reservation lifetime; must be positive (non-null, non-negative, non-zero)
 */
public record ReserveInventoryCommand(
    Long variantId,
    Long warehouseId,
    long quantity,
    String sagaStepId,
    Long orderUuid,
    Duration ttl) {}