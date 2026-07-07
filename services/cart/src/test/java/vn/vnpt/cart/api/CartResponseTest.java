package vn.vnpt.cart.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;

/**
 * Story 2.1 DTO mapping — {@code subtotalCents} is the {@code 0L} pricing-pending placeholder,
 * {@code currency} is the v1 VND default, soft-deleted lines are excluded from the response.
 */
class CartResponseTest {

  private Cart cart(CartStatus status) {
    Cart c = Cart.builder().tenantId("default").userId("u-1").status(status).build();
    c.setUuid(42L);
    c.setVersion(0L);
    c.setCreatedAt(LocalDateTime.of(2026, 7, 7, 11, 30));
    return c;
  }

  private CartLine line(long variant) {
    CartLine l = CartLine.builder().cartUuid(42L).variantId(variant).quantity(2).build();
    l.setUuid(variant);
    l.setIsDeleted(false);
    l.setVersion(0L);
    return l;
  }

  @Test
  void from_mapsCartFields_andSubtotalIsZeroPlaceholder() {
    Cart cart = cart(CartStatus.ACTIVE);
    CartResponse resp = CartResponse.from(cart, List.of());

    assertThat(resp.cartUuid()).isEqualTo(42L);
    assertThat(resp.userId()).isEqualTo("u-1");
    assertThat(resp.status()).isEqualTo("ACTIVE");
    assertThat(resp.subtotalCents()).isZero(); // pricing-pending placeholder
    assertThat(resp.currency()).isEqualTo("VND");
    assertThat(resp.lines()).isEmpty();
    assertThat(resp.createdAt()).isEqualTo(LocalDateTime.of(2026, 7, 7, 11, 30));
  }

  @Test
  void from_filtersOutSoftDeletedLines() {
    Cart cart = cart(CartStatus.ACTIVE);
    CartLine live = line(1001L);
    CartLine deleted = line(2002L);
    deleted.setIsDeleted(true);

    CartResponse resp = CartResponse.from(cart, List.of(live, deleted));

    assertThat(resp.lines()).hasSize(1);
    assertThat(resp.lines().get(0).variantId()).isEqualTo(1001L);
  }

  @Test
  void from_handlesNullStatus() {
    Cart cart = Cart.builder().tenantId("default").userId("u-1").build();
    cart.setUuid(99L);
    cart.setCreatedAt(LocalDateTime.now());

    CartResponse resp = CartResponse.from(cart, List.of());

    assertThat(resp.status()).isNull();
    assertThat(resp.cartUuid()).isEqualTo(99L);
  }
}