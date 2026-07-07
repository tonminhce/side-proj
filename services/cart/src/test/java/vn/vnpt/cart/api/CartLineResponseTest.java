package vn.vnpt.cart.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import vn.vnpt.cart.domain.CartLine;

/** Single-line DTO + soft-delete filtering — Story 2.1. */
class CartLineResponseTest {

  private CartLine line(long uuid, long variant, int qty, boolean deleted) {
    CartLine l = CartLine.builder().cartUuid(1L).variantId(variant).quantity(qty).build();
    l.setUuid(uuid);
    l.setIsDeleted(deleted);
    l.setVersion(0L);
    return l;
  }

  @Test
  void from_mapsAllFields() {
    CartLine line = line(100L, 1001L, 3, false);

    CartLineResponse resp = CartLineResponse.from(line);

    assertThat(resp.lineUuid()).isEqualTo(100L);
    assertThat(resp.variantId()).isEqualTo(1001L);
    assertThat(resp.quantity()).isEqualTo(3);
    assertThat(resp.version()).isEqualTo(0L);
  }

  @Test
  void fromList_filtersSoftDeletedLines() {
    CartLine a = line(1L, 1001L, 1, false);
    CartLine b = line(2L, 2002L, 2, true); // soft-deleted — must be excluded
    CartLine c = line(3L, 3003L, 3, false);

    List<CartLineResponse> out = CartLineResponse.from(List.of(a, b, c));

    assertThat(out).hasSize(2);
    assertThat(out).extracting(CartLineResponse::variantId).containsExactly(1001L, 3003L);
  }

  @Test
  void from_handlesNullQuantityAsZero() {
    // ponytail: legacy rows may carry null quantity — DTO falls back to 0 instead of NPE.
    CartLine line = CartLine.builder().cartUuid(1L).variantId(1001L).build();
    line.setUuid(100L);

    CartLineResponse resp = CartLineResponse.from(line);

    assertThat(resp.quantity()).isZero();
  }
}