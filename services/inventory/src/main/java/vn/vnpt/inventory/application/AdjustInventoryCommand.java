package vn.vnpt.inventory.application;

import vn.vnpt.inventory.domain.InventoryReason;

/**
 * Command for {@link AdjustInventoryUseCase#adjust(AdjustInventoryCommand)}.
 *
 * @param variantId the variant's Snowflake id (cross-service reference to catalog)
 * @param warehouseId the warehouse's Snowflake id
 * @param delta signed delta (positive inbound, negative outbound). Must be non-zero.
 * @param reason the adjustment reason; must be non-null
 */
public record AdjustInventoryCommand(
    Long variantId, Long warehouseId, long delta, InventoryReason reason) {}