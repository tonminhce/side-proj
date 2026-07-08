package vn.vnpt.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.domain.exception.OrderEditWindowClosedException;
import vn.vnpt.order.domain.exception.OrderTerminalStateException;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Tests for cancel-after-pay — Story 4.4 / FR-34.
 */
class CancelOrderUseCaseTest {

  private OrderStateTransitionRepository transitionRepo;
  private OrderPriceSnapshotRepository priceRepo;
  private AppendOrderTransitionUseCase appendUseCase;
  private CancelOrderUseCase cancelUseCase;
  private Clock fixedClock;

  @BeforeEach
  void setUp() {
    transitionRepo = Mockito.mock(OrderStateTransitionRepository.class);
    priceRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    appendUseCase = Mockito.mock(AppendOrderTransitionUseCase.class);
    fixedClock = Clock.fixed(Instant.parse("2026-07-08T00:05:00Z"), ZoneOffset.UTC);
    cancelUseCase = new CancelOrderUseCase(transitionRepo, priceRepo, appendUseCase, fixedClock);
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdAsc(42L))
        .thenReturn(Optional.of(transition(1L, 42L, null, "PLACED",
            LocalDateTime.of(2026, 7, 8, 0, 0, 0))));
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(42L))
        .thenReturn(Optional.of(transition(1L, 42L, null, "PLACED",
            LocalDateTime.of(2026, 7, 8, 0, 0, 0))));
    Mockito.when(transitionRepo.findByOrderUuidOrderByIdAsc(42L))
        .thenReturn(List.of(transition(1L, 42L, null, "PLACED",
            LocalDateTime.of(2026, 7, 8, 0, 0, 0))));
    Mockito.when(priceRepo.findById(42L)).thenReturn(Optional.of(snapshot(42L)));
    Mockito.when(appendUseCase.execute(any())).thenReturn(2L);
  }

  @Test
  void cancel_within30MinAndMatchingVersion_appendsCancelledTransition() {
    long id = cancelUseCase.execute(42L, 1L);
    assertThat(id).isEqualTo(2L);
    Mockito.verify(appendUseCase).execute(any(AppendOrderTransitionCommand.class));
  }

  @Test
  void cancel_after30Min_throwsWindowClosedException() {
    Clock afterWindow = Clock.fixed(Instant.parse("2026-07-08T00:35:00Z"), ZoneOffset.UTC);
    CancelOrderUseCase later = new CancelOrderUseCase(transitionRepo, priceRepo, appendUseCase, afterWindow);

    assertThatThrownBy(() -> later.execute(42L, 1L))
        .isInstanceOf(OrderEditWindowClosedException.class);
  }

  @Test
  void cancel_alreadyCancelled_throwsTerminalStateException() {
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(42L))
        .thenReturn(Optional.of(transition(2L, 42L, "PLACED", "CANCELLED",
            LocalDateTime.of(2026, 7, 8, 0, 5, 0))));

    assertThatThrownBy(() -> cancelUseCase.execute(42L, 2L))
        .isInstanceOf(OrderTerminalStateException.class);
  }

  private static OrderStateTransition transition(
      long id, long orderUuid, String from, String to, LocalDateTime createdAt) {
    return OrderStateTransition.builder()
        .id(id).orderUuid(orderUuid).fromState(from).toState(to)
        .sagaStep("test").eventId(100L + id).createdAt(createdAt)
        .build();
  }

  private static OrderPriceSnapshot snapshot(long orderUuid) {
    return OrderPriceSnapshot.builder()
        .orderUuid(orderUuid).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now()).addressJson(null)
        .build();
  }
}