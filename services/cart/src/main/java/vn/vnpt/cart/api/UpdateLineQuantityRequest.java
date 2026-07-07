package vn.vnpt.cart.api;

/** Request body for {@code PATCH /api/carts/{uuid}/lines/{lineUuid}} (Story 2.1). */
public record UpdateLineQuantityRequest(Integer quantity, Long expectedLineVersion) {}
