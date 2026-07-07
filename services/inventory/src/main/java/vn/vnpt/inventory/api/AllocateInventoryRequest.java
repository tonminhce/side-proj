package vn.vnpt.inventory.api;

/**
 * Request body for {@code POST /api/inventory-allocations}.
 */
public record AllocateInventoryRequest(Long reservationUuid, String sagaStepId) {}