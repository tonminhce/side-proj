package vn.vnpt.checkout.api;

/**
 * Wire shape for {@code CartLineSnapshot} (Story 2.3 / FR-19; Story 2.4 adds unitPriceMinor).
 *
 * <p>Plain record — Jackson 3 deserializes records natively.
 */
public record CartLineSnapshotDto(
    Long variantId, String sellerId, Integer quantity, Long unitPriceMinor) {}