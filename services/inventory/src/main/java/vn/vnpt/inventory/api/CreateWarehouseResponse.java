package vn.vnpt.inventory.api;

import java.time.LocalDateTime;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;

/**
 * Response body for {@code POST /api/inventory-warehouses}.
 */
public record CreateWarehouseResponse(
    Long uuid,
    String code,
    String displayName,
    Region region,
    LocalDateTime createdAt) {

  /** Factory from the persisted entity. */
  public static CreateWarehouseResponse from(Warehouse w) {
    return new CreateWarehouseResponse(
        w.getUuid(), w.getCode(), w.getDisplayName(), w.getRegion(), w.getCreatedAt());
  }
}