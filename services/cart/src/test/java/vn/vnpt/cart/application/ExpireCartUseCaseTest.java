package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.infrastructure.outbox.CartEventPublisher;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

@ExtendWith(MockitoExtension.class)
class ExpireCartUseCaseTest {

  @Mock CartRepository cartRepository;
  @Mock CartLineRepository cartLineRepository;
  @Mock CartEventPublisher cartEventPublisher;
  @InjectMocks ExpireCartUseCase useCase;

  private Cart cart(CartStatus status, Instant expiresAt) {
    Cart c = Cart.builder().tenantId("default").status(status).build();
    c.setUuid(100L);
    c.setExpiresAt(expiresAt);
    return c;
  }

  private CartLine line() {
    CartLine l = CartLine.builder().cartUuid(100L).variantId(1001L).quantity(2).build();
    l.setIsDeleted(false);
    return l;
  }

  @Test
  void expireSingleCart_anonymousCart_transitionsToAbandonedAndEmitsCartExpired() {
    Cart c = cart(CartStatus.ANONYMOUS, Instant.now().minusSeconds(60));
    when(cartRepository.findById(100L)).thenReturn(Optional.of(c));
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(c));
    when(cartLineRepository.findByCartUuid(100L)).thenReturn(List.of(line()));
    when(cartRepository.save(c)).thenReturn(c);

    useCase.expireSingleCart(100L);

    assertThat(c.getStatus()).isEqualTo(CartStatus.ABANDONED);
    ArgumentCaptor<CartStatus> prevStatus = ArgumentCaptor.forClass(CartStatus.class);
    ArgumentCaptor<Integer> lines = ArgumentCaptor.forClass(Integer.class);
    verify(cartEventPublisher).publishCartExpired(eq(c), prevStatus.capture(), lines.capture());
    assertThat(prevStatus.getValue()).isEqualTo(CartStatus.ANONYMOUS);
    assertThat(lines.getValue()).isEqualTo(1);
  }

  @Test
  void expireSingleCart_alreadyTerminalCart_noOp() {
    Cart c = cart(CartStatus.MERGED, Instant.now().minusSeconds(60));
    when(cartRepository.findById(100L)).thenReturn(Optional.of(c));

    useCase.expireSingleCart(100L);

    verify(cartEventPublisher, never()).publishCartExpired(any(), any(), anyInt());
    verify(cartRepository, never()).save(any(Cart.class));
  }

  @Test
  void expireSingleCart_notYetExpired_noOp() {
    Cart c = cart(CartStatus.ACTIVE, Instant.now().plusSeconds(3600));
    when(cartRepository.findById(100L)).thenReturn(Optional.of(c));

    useCase.expireSingleCart(100L);

    verify(cartEventPublisher, never()).publishCartExpired(any(), any(), anyInt());
    verify(cartRepository, never()).save(any(Cart.class));
  }

  @Test
  void expireSingleCart_cartNotFound_noOp() {
    when(cartRepository.findById(404L)).thenReturn(Optional.empty());

    useCase.expireSingleCart(404L);

    verify(cartEventPublisher, never()).publishCartExpired(any(), any(), anyInt());
    verify(cartRepository, never()).save(any(Cart.class));
    verify(cartLineRepository, never()).findByCartUuid(anyLong());
  }
}