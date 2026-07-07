package vn.vnpt.cart.api;

/** Request body for {@code POST /api/carts} (Story 2.1). At least one identifier is required. */
public record CreateCartRequest(String guestCartId, String userId) {}
