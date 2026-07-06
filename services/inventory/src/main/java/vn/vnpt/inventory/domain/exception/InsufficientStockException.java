package vn.vnpt.inventory.domain.exception;

/**
 * Thrown by {@code ReserveInventoryUseCase} when {@code available < requested} — the FR-9
 * binding for the oversell guard (DI-01 root-cause fix).
 *
 * <p>Mapped to HTTP 409 Conflict by {@code ReservationControllerExceptionHandler}. Carries the
 * 4-tuple ({@code variantId}, {@code warehouseId}, {@code requested}, {@code available}) for the
 * saga's retry decision and diagnostic logging.
 *
 * <p>Extends {@link RuntimeException} so Spring's {@code @Transactional} rolls back on it
 * by default.
 */
public class InsufficientStockException extends RuntimeException {

  private final Long variantId;
  private final Long warehouseId;
  private final long requested;
  private final long available;

  public InsufficientStockException(Long variantId, Long warehouseId, long requested, long available) {
    super(
        "Insufficient stock: variantId="
            + variantId
            + " warehouseId="
            + warehouseId
            + " requested="
            + requested
            + " available="
            + available);
    this.variantId = variantId;
    this.warehouseId = warehouseId;
    this.requested = requested;
    this.available = available;
  }

  public Long getVariantId() {
    return variantId;
  }

  public Long getWarehouseId() {
    return warehouseId;
  }

  public long getRequested() {
    return requested;
  }

  public long getAvailable() {
    return available;
  }
}