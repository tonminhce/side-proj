package vn.vnpt.inventory.api;

/**
 * Request body for {@code POST /api/inventory-reservations}.
 *
 * <p>Story 1.7 / FR-10: exactly ONE of {@code warehouseId} / {@code shippingRegion} MUST be
 * supplied — the use case enforces the XOR ({@link
 * vn.vnpt.inventory.application.ReserveInventoryUseCase#reserve}). {@code warehouseId} was
 * required in Story 1.6; in Story 1.7 it's optional so callers can dispatch via region.
 *
 * <p>{@code shippingRegion} is a {@code String} (not the {@code Region} enum) so Jackson
 * can deserialize the literal; the controller converts via {@code Region.valueOf(...)} with
 * {@code IllegalArgumentException → 400} mapping in the exception handler.
 *
 * <p>{@code ttlMinutes} is optional (nullable {@link Integer}); null means "use configured
 * default" (yml {@code inventory.reservation.ttl-minutes}, default 15). The controller converts
 * to a {@link java.time.Duration} before invoking the use case.
 */
public record ReserveInventoryRequest(
    Long variantId,
    Long warehouseId,
    String shippingRegion,
    long quantity,
    String sagaStepId,
    Long orderUuid,
    Integer ttlMinutes) {}