package vn.vnpt.inventory.api;

/**
 * Request body for {@code POST /api/inventory-reservations}.
 *
 * <p>{@code ttlMinutes} is optional (nullable {@link Integer}); null means "use configured
 * default" (yml {@code inventory.reservation.ttl-minutes}, default 15). The controller converts
 * to a {@link java.time.Duration} before invoking the use case.
 */
public record ReserveInventoryRequest(
    Long variantId,
    Long warehouseId,
    long quantity,
    String sagaStepId,
    Long orderUuid,
    Integer ttlMinutes) {}