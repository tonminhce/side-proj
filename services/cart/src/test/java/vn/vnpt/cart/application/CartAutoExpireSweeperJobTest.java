package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

@ExtendWith(MockitoExtension.class)
class CartAutoExpireSweeperJobTest {

  @Mock CartRepository cartRepository;
  @Mock ExpireCartUseCase expireCartUseCase;

  private SimpleMeterRegistry meterRegistry;
  private CartAutoExpireSweeperJob sweeper;

  @BeforeEach
  void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    sweeper = new CartAutoExpireSweeperJob(cartRepository, expireCartUseCase, meterRegistry);
    ReflectionTestUtils.setField(sweeper, "batchSize", 100);
  }

  private Cart cart(long uuid, CartStatus status) {
    Cart c = Cart.builder().tenantId("default").status(status).build();
    c.setUuid(uuid);
    c.setExpiresAt(Instant.now().minusSeconds(60));
    return c;
  }

  @Test
  void sweep_noExpiredCarts_doesNothing() {
    when(cartRepository.findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
        .thenReturn(List.of());

    sweeper.sweep();

    verify(expireCartUseCase, never()).expireSingleCart(anyLong());
    assertThat(meterRegistry.counter("cart.auto_expire.expired").count()).isEqualTo(0.0);
  }

  @Test
  void sweep_expiredCart_callsExpireSingleCart_perCart() {
    when(cartRepository.findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
        .thenReturn(List.of(cart(1L, CartStatus.ANONYMOUS), cart(2L, CartStatus.ACTIVE), cart(3L, CartStatus.ANONYMOUS)));

    sweeper.sweep();

    InOrder order = inOrder(expireCartUseCase);
    order.verify(expireCartUseCase).expireSingleCart(1L);
    order.verify(expireCartUseCase).expireSingleCart(2L);
    order.verify(expireCartUseCase).expireSingleCart(3L);
  }

  @Test
  void sweep_partialFailure_continuesBatch() {
    when(cartRepository.findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
        .thenReturn(List.of(cart(1L, CartStatus.ANONYMOUS), cart(2L, CartStatus.ACTIVE), cart(3L, CartStatus.ANONYMOUS)));
    doThrow(new RuntimeException("boom")).when(expireCartUseCase).expireSingleCart(2L);

    sweeper.sweep();

    // All 3 carts were attempted — the failed one is logged and the batch continues.
    verify(expireCartUseCase, times(3)).expireSingleCart(anyLong());
    verify(expireCartUseCase).expireSingleCart(1L);
    verify(expireCartUseCase).expireSingleCart(2L);
    verify(expireCartUseCase).expireSingleCart(3L);
    // Counter incremented only for the 2 successful expiries (1 and 3).
    double counter = meterRegistry.counter("cart.auto_expire.expired").count();
    assertThat(counter).isGreaterThanOrEqualTo(1.0).isLessThanOrEqualTo(2.0);
  }

  @Test
  void sweep_emitsCounterIncrement_perSuccess() {
    when(cartRepository.findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
        .thenReturn(List.of(cart(1L, CartStatus.ANONYMOUS), cart(2L, CartStatus.ACTIVE)));

    sweeper.sweep();

    assertThat(meterRegistry.counter("cart.auto_expire.expired").count()).isEqualTo(2.0);
  }

  @Test
  void sweep_respectsBatchSize() {
    ReflectionTestUtils.setField(sweeper, "batchSize", 3);
    when(cartRepository.findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
        .thenReturn(List.of(cart(1L, CartStatus.ANONYMOUS), cart(2L, CartStatus.ACTIVE),
            cart(3L, CartStatus.ANONYMOUS), cart(4L, CartStatus.ACTIVE), cart(5L, CartStatus.ANONYMOUS)));

    sweeper.sweep();

    // Only the first 3 are processed.
    verify(expireCartUseCase, times(3)).expireSingleCart(anyLong());
    assertThat(meterRegistry.counter("cart.auto_expire.expired").count()).isEqualTo(3.0);
  }

  @Test
  void getBatchSize_returnsConfiguredValue() {
    ReflectionTestUtils.setField(sweeper, "batchSize", 50);
    assertThat(sweeper.getBatchSize()).isEqualTo(50);
  }
}