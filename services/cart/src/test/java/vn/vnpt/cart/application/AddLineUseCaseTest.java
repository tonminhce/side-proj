package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

@ExtendWith(MockitoExtension.class)
class AddLineUseCaseTest {

  @Mock CartRepository cartRepository;
  @Mock CartLineRepository cartLineRepository;
  @InjectMocks AddLineUseCase useCase;

  private Cart cart() {
    Cart c = Cart.builder().tenantId("default").userId("u-1").status(CartStatus.ACTIVE).build();
    c.setUuid(100L);
    c.setVersion(0L);
    return c;
  }

  @Test
  void add_addsNewLine_andIncrementsVersion() {
    Cart cart = cart();
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));
    when(cartLineRepository.findActiveByCartUuidAndVariantId(100L, 1001L)).thenReturn(Optional.empty());
    when(cartLineRepository.save(any(CartLine.class))).thenAnswer(inv -> inv.getArgument(0));
    when(cartRepository.save(cart)).thenReturn(cart);

    Cart result = useCase.addLine(100L, 1001L, 2, 0L);

    assertThat(result).isSameAs(cart);
    ArgumentCaptor<CartLine> captor = ArgumentCaptor.forClass(CartLine.class);
    verify(cartLineRepository).save(captor.capture());
    assertThat(captor.getValue().getVariantId()).isEqualTo(1001L);
    assertThat(captor.getValue().getQuantity()).isEqualTo(2);
    verify(cartRepository).save(cart); // force-increment path
  }

  @Test
  void add_existingVariant_sumsQuantity() {
    Cart cart = cart();
    CartLine existing = CartLine.builder().cartUuid(100L).variantId(1001L).quantity(3).build();
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));
    when(cartLineRepository.findActiveByCartUuidAndVariantId(100L, 1001L)).thenReturn(Optional.of(existing));
    when(cartLineRepository.save(any(CartLine.class))).thenAnswer(inv -> inv.getArgument(0));
    when(cartRepository.save(cart)).thenReturn(cart);

    useCase.addLine(100L, 1001L, 2, 0L);

    assertThat(existing.getQuantity()).isEqualTo(5);
    verify(cartLineRepository).save(existing);
  }

  @Test
  void add_concurrentEdit_throwsCartVersionConflictException() {
    Cart cart = cart();
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));
    when(cartLineRepository.findActiveByCartUuidAndVariantId(100L, 1001L)).thenReturn(Optional.empty());
    when(cartLineRepository.save(any(CartLine.class))).thenAnswer(inv -> inv.getArgument(0));
    when(cartRepository.save(cart)).thenThrow(new ObjectOptimisticLockingFailureException(Cart.class, 100L));
    when(cartRepository.findById(100L)).thenReturn(Optional.of(cart));

    assertThatThrownBy(() -> useCase.addLine(100L, 1001L, 2, 0L))
        .isInstanceOf(CartVersionConflictException.class);
  }

  @Test
  void add_expectedVersionMismatch_throwsCartVersionConflictException() {
    // AC #5 — explicit expectedVersion pre-check (separate from JPA catch path).
    Cart cart = cart();
    cart.setVersion(3L);
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));

    assertThatThrownBy(() -> useCase.addLine(100L, 1001L, 2, 0L))
        .isInstanceOf(CartVersionConflictException.class)
        .hasMessageContaining("expected=0")
        .hasMessageContaining("actual=3");
  }

  @Test
  void add_zeroOrNegativeQuantity_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> useCase.addLine(100L, 1001L, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
    assertThatThrownBy(() -> useCase.addLine(100L, 1001L, -1, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void add_cartNotFound_throwsCartNotFoundException() {
    when(cartRepository.findAndLockByUuid(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.addLine(404L, 1001L, 2, null))
        .isInstanceOf(CartNotFoundException.class);
  }

  @Test
  void add_afterRemove_sameVariant_createsFreshLine_notUpdateTombstone() {
    // Regression: remove-then-re-add of the same variant must NOT sum quantity onto a soft-deleted
    // tombstone (the user would never see the line). The active-only lookup ensures a fresh row.
    Cart cart = cart();
    CartLine tombstone = CartLine.builder().cartUuid(100L).variantId(1001L).quantity(3).build();
    tombstone.setIsDeleted(true);
    when(cartRepository.findAndLockByUuid(100L)).thenReturn(Optional.of(cart));
    when(cartLineRepository.findActiveByCartUuidAndVariantId(100L, 1001L)).thenReturn(Optional.empty());
    when(cartLineRepository.save(any(CartLine.class))).thenAnswer(inv -> inv.getArgument(0));
    when(cartRepository.save(cart)).thenReturn(cart);

    useCase.addLine(100L, 1001L, 2, 0L);

    // The saved line is a fresh row (quantity=2), NOT the tombstone with summed quantity=5.
    ArgumentCaptor<CartLine> captor = ArgumentCaptor.forClass(CartLine.class);
    verify(cartLineRepository).save(captor.capture());
    assertThat(captor.getValue().getQuantity()).isEqualTo(2);
    assertThat(captor.getValue().getIsDeleted()).isNotEqualTo(true);
    // Tombstone was untouched.
    assertThat(tombstone.getQuantity()).isEqualTo(3);
  }
}
