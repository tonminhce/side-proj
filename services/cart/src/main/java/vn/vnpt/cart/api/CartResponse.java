package vn.vnpt.cart.api;

import java.time.LocalDateTime;
import java.util.List;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;

/**
 * Response DTO for a cart (Story 2.1 / AC #3).
 *
 * <p>{@code subtotalCents} is a placeholder {@code 0L} — the pricing question (FR-15 + FR-65/FR-67)
 * is deferred to Sprint 5's PricingService stub. The field exists in the DTO; the value is 0 until
 * PricingService lands ("cart total computed on read").
 */
public record CartResponse(
    Long cartUuid,
    String userId,
    String guestCartId,
    String status,
    Long version,
    List<CartLineResponse> lines,
    long subtotalCents,
    String currency,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  public static CartResponse from(Cart cart, List<CartLine> lines) {
    return new CartResponse(
        cart.getUuid(),
        cart.getUserId(),
        cart.getGuestCartId(),
        cart.getStatus() == null ? null : cart.getStatus().name(),
        cart.getVersion(),
        CartLineResponse.from(lines),
        0L, // pricing-pending — PricingService (Sprint 5) fills this
        "VND",
        cart.getCreatedAt(),
        cart.getUpdatedAt());
  }
}
