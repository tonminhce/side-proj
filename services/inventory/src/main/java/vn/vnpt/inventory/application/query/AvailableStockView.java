package vn.vnpt.inventory.application.query;

/**
 * Read-side projection of available stock — Story 1.6 / FR-9.
 *
 * <p>{@code available = onHand - activeReservations} for a {@code (variantId, warehouseId)}
 * pair. Used by {@code ReserveInventoryUseCase} as the pre-flight check before the
 * {@code SELECT … FOR UPDATE} lock.
 *
 * @param variantId the variant's Snowflake id
 * @param warehouseId the warehouse's Snowflake id
 * @param onHand sum of deltas (signed)
 * @param activeReservations sum of {@code quantity} across {@code ACTIVE} reservations
 * @param available {@code onHand - activeReservations}; may be 0 or negative in edge cases
 */
public record AvailableStockView(
    Long variantId,
    Long warehouseId,
    Long onHand,
    Long activeReservations,
    Long available) {}