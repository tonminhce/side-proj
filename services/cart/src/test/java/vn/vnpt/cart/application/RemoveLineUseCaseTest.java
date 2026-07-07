package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.CartLineNotFoundException;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

@ExtendWith(MockitoExtension.class)
class RemoveLineUseCaseTest {

  @Mock CartRepository cartRepository;
  @Mock CartLineRepository cartLineRepository;
  @InjectMocks RemoveLineUseCase useCase;

  private Cart cart() {
    Cart c = Cart.builder().tenantId("default").userId("u-1").status(CartStatus.ACTIVE).build();
    c.setUuid(100L);
    c.setVersion(0L);
    return c;
  }

  @Test
  void remove_softDeletesLine_andIncrementsCartVersion() {
    Cart cart = cart();
    CartLine line = CartLine.builder().cartUuid(100L).variantId(1001L).quantity(1).build();
    line.setUuid(500L);
    line.setIsDeleted(false);
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));
    when(cartLineRepository.findById(500L)).thenReturn(Optional.of(line));
    when(cartLineRepository.save(any(CartLine.class))).thenAnswer(inv -> inv.getArgument(0));
    when(cartRepository.save(cart)).thenReturn(cart);

    Cart result = useCase.removeLine(100L, 500L, 0L);

    assertThat(result).isSameAs(cart);
    assertThat(line.getIsDeleted()).isTrue();
    verify(cartLineRepository).save(line);
    verify(cartRepository).save(cart);
  }

  @Test
  void remove_lineNotFound_throwsCartLineNotFoundException() {
    Cart cart = cart();
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));
    when(cartLineRepository.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.removeLine(100L, 999L, 0L))
        .isInstanceOf(CartLineNotFoundException.class);
  }

  @Test
  void remove_expectedCartVersionMismatch_throwsCartVersionConflictException() {
    // AC #5 — explicit expectedCartVersion pre-check.
    Cart cart = cart();
    cart.setVersion(7L);
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));

    assertThatThrownBy(() -> useCase.removeLine(100L, 500L, 0L))
        .isInstanceOf(CartVersionConflictException.class);
  }

  @Test
  void remove_cartNotFound_throwsCartNotFoundException() {
    when(cartRepository.findAndLockByUuid(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.removeLine(404L, 500L, null))
        .isInstanceOf(CartNotFoundException.class);
  }
}
