package vn.vnpt.cart.api;

/** Request body for {@code POST /api/carts/{uuid}/lines} (Story 2.1). */
public record AddLineRequest(Long variantId, Integer quantity, Long expectedCartVersion) {}
