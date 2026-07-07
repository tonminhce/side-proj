package vn.vnpt.inventory.api;

import vn.vnpt.inventory.domain.InventoryLedgerEntry;

/**
 * Response body for {@code POST /api/inventory-shipments}.
 */
public record ShipInventoryResponse(
    Long ledgerEntryUuid, Long variantId, Long warehouseId, long delta, String reason) {

  public static ShipInventoryResponse from(InventoryLedgerEntry e) {
    return new ShipInventoryResponse(
        e.getUuid(), e.getVariantId(), e.getWarehouseId(), e.getDelta(), e.getReason());
  }
}