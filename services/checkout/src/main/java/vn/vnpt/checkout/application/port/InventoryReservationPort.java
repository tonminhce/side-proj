package vn.vnpt.checkout.application.port;

import java.time.Instant;
import java.util.List;

/**
 * Inventory reservation port — Story 2.5 / FR-22 (ADR-12).
 *
 * <p>The saga calls this port to reserve stock for the {@code stock.reserve} step. The adapter
 * ({@code vn.vnpt.checkout.infrastructure.inventory.JavaDirectInventoryReservationAdapter}) is a
 * thin wrapper around {@code inventory.application.ReserveInventoryUseCase.reserve(...)}; the port
 * shape mirrors {@code inventory.application.ReserveInventoryCommand} without leaking it across the
 * boundary.
 */
public interface InventoryReservationPort {

  /** Cart line shape the port accepts — variant id + quantity (unitPriceMinor discarded). */
  record LineItem(Long variantId, Long quantity) {}

  /** Reservation result — what the saga needs to emit {@code order.stock_reserved}. */
  record Result(Long reservationUuid, Long warehouseId, Instant expiresAt) {}

  /**
   * Reserve stock for the saga's {@code stock.reserve} step.
   *
   * @param orderUuid Snowflake id of the order (used to derive a stable idempotency key)
   * @param items cart lines (unitPriceMinor is dropped; only variantId + quantity are used)
   * @param idempotencyKey stable tuple per ADR-11 — the saga passes {@code orderUuid + ":stock.reserve"}
   */
  Result reserve(Long orderUuid, List<LineItem> items, String idempotencyKey);
}