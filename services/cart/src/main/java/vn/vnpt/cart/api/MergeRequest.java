package vn.vnpt.cart.api;

/** Request body for {@code POST /api/carts/merge} (Story 2.1 / AC #6). */
public record MergeRequest(String guestCartId, String userId) {}
