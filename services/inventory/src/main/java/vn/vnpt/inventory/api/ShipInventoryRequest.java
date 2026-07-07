package vn.vnpt.inventory.api;

/**
 * Request body for {@code POST /api/inventory-shipments}.
 */
public record ShipInventoryRequest(
    Long variantId, Long warehouseId, long quantity, String sagaStepId) {}