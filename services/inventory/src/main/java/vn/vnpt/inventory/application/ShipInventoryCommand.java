package vn.vnpt.inventory.application;

/**
 * Command for {@link ShipInventoryUseCase#ship(ShipInventoryCommand)}.
 *
 * @param variantId the variant's Snowflake id (cross-service reference to catalog)
 * @param warehouseId the warehouse's Snowflake id (where stock is being picked)
 * @param quantity the units being shipped (must be {@code > 0})
 * @param sagaStepId ADR-11 idempotency key for the saga step
 */
public record ShipInventoryCommand(
    Long variantId, Long warehouseId, long quantity, String sagaStepId) {}