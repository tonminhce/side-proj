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
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.exception.OrderEditWindowClosedException;
import vn.vnpt.order.domain.exception.OrderTerminalStateException;
import vn.vnpt.order.domain.exception.OrderVersionMismatchException;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Tests for amend-after-pay — Story 4.4 / FR-34.
 */
class AmendOrderAddressUseCaseTest {

  private OrderStateTransitionRepository transitionRepo;
  private OrderPriceSnapshotRepository priceRepo;
  private AppendOrderTransitionUseCase appendUseCase;
  private AmendOrderAddressUseCase amendUseCase;
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  private Clock fixedClock;

  @BeforeEach
  void setUp() {
    transitionRepo = Mockito.mock(OrderStateTransitionRepository.class);
    priceRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    appendUseCase = Mockito.mock(AppendOrderTransitionUseCase.class);
    jdbcTemplate = Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
    // Genesis at 2026-07-08T00:00:00Z; window closes at 2026-07-08T00:30:00Z.
    fixedClock = Clock.fixed(Instant.parse("2026-07-08T00:05:00Z"), ZoneOffset.UTC);
    amendUseCase = new AmendOrderAddressUseCase(
        transitionRepo, priceRepo, appendUseCase, fixedClock, jdbcTemplate);
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdAsc(42L))
        .thenReturn(Optional.of(transition(1L, 42L, null, "PLACED",
            LocalDateTime.of(2026, 7, 8, 0, 0, 0).plusHours(0))));
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
  void amend_addressWithin30MinAndMatchingVersion_appendsAmendedTransition() {
    long id = amendUseCase.execute(42L, 1L, "{\"line1\":\"123 Main St\"}");

    assertThat(id).isEqualTo(2L);
    Mockito.verify(appendUseCase).execute(any(AppendOrderTransitionCommand.class));
  }

  @Test
  void amend_addressAfter30Min_throwsWindowClosedException() {
    Clock afterWindow = Clock.fixed(Instant.parse("2026-07-08T00:35:00Z"), ZoneOffset.UTC);
    AmendOrderAddressUseCase later = new AmendOrderAddressUseCase(
        transitionRepo, priceRepo, appendUseCase, afterWindow, jdbcTemplate);

    assertThatThrownBy(() -> later.execute(42L, 1L, "{}"))
        .isInstanceOf(OrderEditWindowClosedException.class);
  }

  @Test
  void amend_addressWithStaleVersion_throwsVersionMismatchException() {
    Mockito.when(transitionRepo.findByOrderUuidOrderByIdAsc(42L))
        .thenReturn(List.of(
            transition(1L, 42L, null, "PLACED",
                LocalDateTime.of(2026, 7, 8, 0, 0, 0)),
            transition(2L, 42L, "PLACED", "PAID",
                LocalDateTime.of(2026, 7, 8, 0, 5, 0))));

    assertThatThrownBy(() -> amendUseCase.execute(42L, 1L, "{}"))
        .isInstanceOf(OrderVersionMismatchException.class);
  }

  @Test
  void amend_alreadyCancelledOrder_throwsTerminalStateException() {
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(42L))
        .thenReturn(Optional.of(transition(2L, 42L, "PLACED", "CANCELLED",
            LocalDateTime.of(2026, 7, 8, 0, 5, 0))));

    assertThatThrownBy(() -> amendUseCase.execute(42L, 2L, "{}"))
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