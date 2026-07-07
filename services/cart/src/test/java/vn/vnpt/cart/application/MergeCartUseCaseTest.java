package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import vn.vnpt.cart.domain.CartMergeLog;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.AnonymousCartOwnershipConflictException;
import vn.vnpt.cart.infrastructure.outbox.CartEventPublisher;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartMergeLogRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

@ExtendWith(MockitoExtension.class)
class MergeCartUseCaseTest {

  @Mock CartRepository cartRepository;
  @Mock CartLineRepository cartLineRepository;
  @Mock CartMergeLogRepository cartMergeLogRepository;
  @Mock GetOrCreateCartUseCase getOrCreateCartUseCase;
  @Mock CartEventPublisher cartEventPublisher;
  @InjectMocks MergeCartUseCase useCase;

  private static final String GUEST = "guest-1";
  private static final String USER = "user-abc-123";

  private Cart source() {
    Cart c = Cart.builder().tenantId("default").guestCartId(GUEST).status(CartStatus.ANONYMOUS).build();
    c.setUuid(100L);
    return c;
  }

  private Cart target() {
    Cart c = Cart.builder().tenantId("default").userId(USER).status(CartStatus.ACTIVE).build();
    c.setUuid(200L);
    return c;
  }

  private CartLine srcLine(long variant, int qty) {
    CartLine l = CartLine.builder().cartUuid(100L).variantId(variant).quantity(qty).build();
    l.setIsDeleted(false);
    return l;
  }

  @Test
  void merge_firstCall_transfersLines_andEmitsCartMerged() {
    Cart source = source();
    Cart target = target();
    when(cartMergeLogRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(cartMergeLogRepository.findByGuestCartId(GUEST)).thenReturn(List.of());
    when(getOrCreateCartUseCase.getOrCreate(null, USER)).thenReturn(target);
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", GUEST, CartStatus.ANONYMOUS))
        .thenReturn(Optional.of(source));
    when(cartLineRepository.findByCartUuid(100L)).thenReturn(List.of(srcLine(1001L, 2)));
    when(cartLineRepository.findByCartUuidAndVariantId(200L, 1001L)).thenReturn(Optional.empty());

    MergeResult result = useCase.merge(GUEST, USER);

    assertThat(result.alreadyMerged()).isFalse();
    assertThat(result.targetCart()).isSameAs(target);
    assertThat(source.getStatus()).isEqualTo(CartStatus.MERGED);
    verify(cartEventPublisher).publishCartMerged(eq(source), eq(target), eq(1));
  }

  @Test
  void merge_retrySameKey_isIdempotent_returnsSameTargetCart() {
    Cart target = target();
    when(cartMergeLogRepository.findByIdempotencyKey(any()))
        .thenReturn(Optional.of(new CartMergeLog()));
    when(getOrCreateCartUseCase.getOrCreate(null, USER)).thenReturn(target);

    MergeResult result = useCase.merge(GUEST, USER);

    assertThat(result.alreadyMerged()).isTrue();
    assertThat(result.targetCart()).isSameAs(target);
    verify(cartEventPublisher, never()).publishCartMerged(any(), any(), anyInt());
  }

  @Test
  void merge_differentUserSameGuestCart_returns409Conflict() {
    CartMergeLog otherUserLog = new CartMergeLog();
    otherUserLog.setUserId("user-A");
    otherUserLog.setGuestCartId(GUEST);
    when(cartMergeLogRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(cartMergeLogRepository.findByGuestCartId(GUEST)).thenReturn(List.of(otherUserLog));

    assertThatThrownBy(() -> useCase.merge(GUEST, "user-B"))
        .isInstanceOf(AnonymousCartOwnershipConflictException.class)
        .hasMessageContaining(GUEST);
  }

  @Test
  void merge_anonymousCartExpired_returns200WithEmptyUserCart() {
    Cart target = target();
    when(cartMergeLogRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(cartMergeLogRepository.findByGuestCartId(GUEST)).thenReturn(List.of());
    when(getOrCreateCartUseCase.getOrCreate(null, USER)).thenReturn(target);
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", GUEST, CartStatus.ANONYMOUS))
        .thenReturn(Optional.empty());

    MergeResult result = useCase.merge(GUEST, USER);

    assertThat(result.alreadyMerged()).isFalse();
    assertThat(result.targetCart()).isSameAs(target);
    verify(cartEventPublisher, never()).publishCartMerged(any(), any(), anyInt());
  }

  @Test
  void merge_sumsQuantitiesForSharedVariant() {
    Cart source = source();
    Cart target = target();
    CartLine targetLine = CartLine.builder().cartUuid(200L).variantId(1001L).quantity(3).build();
    when(cartMergeLogRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(cartMergeLogRepository.findByGuestCartId(GUEST)).thenReturn(List.of());
    when(getOrCreateCartUseCase.getOrCreate(null, USER)).thenReturn(target);
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", GUEST, CartStatus.ANONYMOUS))
        .thenReturn(Optional.of(source));
    when(cartLineRepository.findByCartUuid(100L)).thenReturn(List.of(srcLine(1001L, 2)));
    when(cartLineRepository.findByCartUuidAndVariantId(200L, 1001L)).thenReturn(Optional.of(targetLine));

    useCase.merge(GUEST, USER);

    assertThat(targetLine.getQuantity()).isEqualTo(5);
    verify(cartLineRepository).save(targetLine);
  }

  @Test
  void merge_emitsCartMergedEvent_withHmacSignature() {
    Cart source = source();
    Cart target = target();
    when(cartMergeLogRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(cartMergeLogRepository.findByGuestCartId(GUEST)).thenReturn(List.of());
    when(getOrCreateCartUseCase.getOrCreate(null, USER)).thenReturn(target);
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", GUEST, CartStatus.ANONYMOUS))
        .thenReturn(Optional.of(source));
    when(cartLineRepository.findByCartUuid(100L)).thenReturn(List.of(srcLine(1001L, 2)));
    when(cartLineRepository.findByCartUuidAndVariantId(200L, 1001L)).thenReturn(Optional.empty());

    useCase.merge(GUEST, USER);

    // CartEventPublisher (mocked here) is responsible for HS256 signing over the JCS payload;
    // its own signing is covered by CartMergedEventTest + the runtime smoke. Here we assert the
    // merge routes the emission through the publisher (Rule 6 boundary contract).
    ArgumentCaptor<Integer> count = ArgumentCaptor.forClass(Integer.class);
    verify(cartEventPublisher).publishCartMerged(eq(source), eq(target), count.capture());
    assertThat(count.getValue()).isEqualTo(1);
  }

  @Test
  void merge_blankGuestCartIdOrUserId_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> useCase.merge(null, USER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> useCase.merge("", USER))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> useCase.merge(GUEST, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> useCase.merge(GUEST, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void merge_idempotencyKeyRace_returnsTargetAsRetry() {
    // Concurrent merge wins the UNIQUE constraint → DataIntegrityViolationException → retry path.
    Cart target = target();
    when(cartMergeLogRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(cartMergeLogRepository.findByGuestCartId(GUEST)).thenReturn(List.of());
    when(getOrCreateCartUseCase.getOrCreate(null, USER)).thenReturn(target);
    when(cartRepository.findByTenantIdAndGuestCartIdAndStatus("default", GUEST, CartStatus.ANONYMOUS))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("uq violation"))
        .when(cartMergeLogRepository).save(any());

    MergeResult result = useCase.merge(GUEST, USER);

    assertThat(result.alreadyMerged()).isTrue();
    assertThat(result.targetCart()).isSameAs(target);
    verify(cartEventPublisher, never()).publishCartMerged(any(), any(), anyInt());
  }
}
