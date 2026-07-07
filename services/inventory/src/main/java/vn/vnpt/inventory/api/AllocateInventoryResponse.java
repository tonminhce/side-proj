package vn.vnpt.inventory.api;

import java.time.Instant;
import java.time.LocalDateTime;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;

/**
 * Response body for {@code POST /api/inventory-allocations}.
 */
public record AllocateInventoryResponse(
    Long reservationUuid,
    Long variantId,
    Long warehouseId,
    long quantity,
    String status,
    Instant expiresAt,
    String sagaStepId,
    Long orderUuid,
    LocalDateTime createdAt) {

  /** Factory from the persisted entity. */
  public static AllocateInventoryResponse from(InventoryReservation r) {
    return new AllocateInventoryResponse(
        r.getUuid(),
        r.getVariantId(),
        r.getWarehouseId(),
        r.getQuantity(),
        r.getStatus() == null ? ReservationStatus.ACTIVE.name() : r.getStatus().name(),
        r.getExpiresAt(),
        r.getSagaStepId(),
        r.getOrderUuid(),
        r.getCreatedAt());
  }
}