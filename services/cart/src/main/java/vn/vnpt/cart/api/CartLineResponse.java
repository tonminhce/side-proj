package vn.vnpt.cart.api;

import java.util.List;
import vn.vnpt.cart.domain.CartLine;

/** Response DTO for a single cart line (Story 2.1 / AC #3). */
public record CartLineResponse(
    Long lineUuid, Long variantId, String sellerId, int quantity, Long version) {

  public static CartLineResponse from(CartLine line) {
    return new CartLineResponse(
        line.getUuid(),
        line.getVariantId(),
        line.getSellerId(),
        line.getQuantity() == null ? 0 : line.getQuantity(),
        line.getVersion());
  }

  public static List<CartLineResponse> from(List<CartLine> lines) {
    return lines.stream()
        .filter(l -> !Boolean.TRUE.equals(l.getIsDeleted()))
        .map(CartLineResponse::from)
        .toList();
  }
}
