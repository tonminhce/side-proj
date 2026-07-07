package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.infrastructure.repository.CartRepository;
import vn.vnpt.util.component.softdelete.validator.UkValidator;

@ExtendWith(MockitoExtension.class)
class GetOrCreateCartUseCaseTest {

  @Mock CartRepository cartRepository;
  @Mock UkValidator ukValidator;
  @InjectMocks GetOrCreateCartUseCase useCase;

  @Test
  void getOrCreate_anonymousCart_returnsExisting() {
    Cart existing = Cart.builder().guestCartId("g-1").status(CartStatus.ANONYMOUS).build();
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", "g-1", CartStatus.ANONYMOUS))
        .thenReturn(Optional.of(existing));

    Cart result = useCase.getOrCreate("g-1", null);

    assertThat(result).isSameAs(existing);
    verify(cartRepository, never()).save(any());
  }

  @Test
  void getOrCreate_anonymousCart_createsNewWithGuestCartId() {
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", "g-2", CartStatus.ANONYMOUS))
        .thenReturn(Optional.empty());
    when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

    Cart result = useCase.getOrCreate("g-2", null);

    assertThat(result.getGuestCartId()).isEqualTo("g-2");
    assertThat(result.getStatus()).isEqualTo(CartStatus.ANONYMOUS);
    verify(ukValidator).validate(any(Cart.class));
  }

  @Test
  void getOrCreate_userBound_returnsExisting() {
    Cart existing = Cart.builder().userId("u-1").status(CartStatus.ACTIVE).build();
    when(cartRepository.findByTenantIdAndUserIdAndStatus("default", "u-1", CartStatus.ACTIVE))
        .thenReturn(Optional.of(existing));

    Cart result = useCase.getOrCreate(null, "u-1");

    assertThat(result).isSameAs(existing);
    verify(cartRepository, never()).save(any());
  }

  @Test
  void getOrCreate_neitherIdProvided_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> useCase.getOrCreate(null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void findByUuid_returnsCart() {
    Cart existing = Cart.builder().userId("u-1").status(CartStatus.ACTIVE).build();
    existing.setUuid(42L);
    when(cartRepository.findById(42L)).thenReturn(Optional.of(existing));

    Cart result = useCase.findByUuid(42L);

    assertThat(result).isSameAs(existing);
  }

  @Test
  void findByUuid_notFound_throwsCartNotFoundException() {
    when(cartRepository.findById(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.findByUuid(404L))
        .isInstanceOf(CartNotFoundException.class)
        .hasMessageContaining("404");
  }
}
