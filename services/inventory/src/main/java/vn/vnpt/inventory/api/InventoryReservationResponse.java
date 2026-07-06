package vn.vnpt.inventory.api;

import java.time.Instant;
import java.time.LocalDateTime;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;

/**
 * Response body for {@code POST /api/inventory-reservations}.
 *
 * <p>{@code expiresAt} is an {@link Instant}; Spring's Jackson 3 default maps Instants to
 * ISO-8601 strings. {@code createdAt} is a {@link LocalDateTime} (matching {@code
 * BaseEntity.createdAt}).
 */
public record InventoryReservationResponse(
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
  public static InventoryReservationResponse from(InventoryReservation r) {
    return new InventoryReservationResponse(
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