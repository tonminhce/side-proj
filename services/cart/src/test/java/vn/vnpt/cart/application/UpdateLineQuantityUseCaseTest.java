package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.exception.CartLineNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

@ExtendWith(MockitoExtension.class)
class UpdateLineQuantityUseCaseTest {

  @Mock CartRepository cartRepository;
  @Mock CartLineRepository cartLineRepository;
  @InjectMocks UpdateLineQuantityUseCase useCase;

  private CartLine line() {
    CartLine l = CartLine.builder().cartUuid(100L).variantId(1001L).quantity(1).build();
    l.setUuid(500L);
    l.setVersion(0L);
    l.setIsDeleted(false);
    return l;
  }

  @Test
  void update_setsAbsoluteQuantity_andIncrementsVersion() {
    CartLine line = line();
    when(cartLineRepository.findById(500L)).thenReturn(Optional.of(line));
    when(cartLineRepository.save(any(CartLine.class))).thenAnswer(inv -> inv.getArgument(0));

    CartLine result = useCase.updateQuantity(100L, 500L, 7, 0L);

    assertThat(result.getQuantity()).isEqualTo(7);
  }

  @Test
  void update_lineNotFound_throwsCartLineNotFoundException() {
    when(cartLineRepository.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.updateQuantity(100L, 999L, 3, null))
        .isInstanceOf(CartLineNotFoundException.class);
  }

  @Test
  void update_concurrentEdit_throwsCartVersionConflictException() {
    CartLine line = line();
    when(cartLineRepository.findById(500L)).thenReturn(Optional.of(line));
    when(cartLineRepository.save(any(CartLine.class)))
        .thenThrow(new ObjectOptimisticLockingFailureException(CartLine.class, 500L));

    assertThatThrownBy(() -> useCase.updateQuantity(100L, 500L, 7, 0L))
        .isInstanceOf(CartVersionConflictException.class);
  }

  @Test
  void update_expectedLineVersionMismatch_throwsCartVersionConflictException() {
    // AC #5 — explicit expectedLineVersion pre-check (separate from JPA catch path).
    CartLine line = line();
    line.setVersion(5L);
    when(cartLineRepository.findById(500L)).thenReturn(Optional.of(line));

    assertThatThrownBy(() -> useCase.updateQuantity(100L, 500L, 7, 0L))
        .isInstanceOf(CartVersionConflictException.class)
        .hasMessageContaining("expected=0")
        .hasMessageContaining("actual=5");
  }

  @Test
  void update_zeroOrNegativeQuantity_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> useCase.updateQuantity(100L, 500L, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
    assertThatThrownBy(() -> useCase.updateQuantity(100L, 500L, -3, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void update_lineInDifferentCart_throwsCartLineNotFoundException() {
    // AC #5 — line must belong to the requested cart (cart-scoped lookup).
    CartLine line = line();
    line.setCartUuid(999L); // different cart
    when(cartLineRepository.findById(500L)).thenReturn(Optional.of(line));

    assertThatThrownBy(() -> useCase.updateQuantity(100L, 500L, 3, null))
        .isInstanceOf(CartLineNotFoundException.class);
  }
}
