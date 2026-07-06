package vn.vnpt.inventory.domain.exception;

/**
 * Thrown when {@code AdjustInventoryUseCase} is called with a {@code warehouseId} that does not
 * exist. Mirrors Story 1.2's domain-exception pattern.
 */
public class WarehouseNotFoundException extends RuntimeException {

  public WarehouseNotFoundException(Long warehouseId) {
    super("Warehouse not found: uuid=" + warehouseId);
  }
}