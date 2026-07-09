package vn.vnpt.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.application.port.OrderTransitionAppender;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.OrderTransitionValidator;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Unit tests for {@link AppendOrderTransitionUseCase} — Story 4.1.
 */
class AppendOrderTransitionUseCaseTest {

  private OrderStateTransitionRepository transitionRepo;
  private OrderPriceSnapshotRepository priceRepo;
  private OrderTransitionAppender appender;
  private AppendOrderTransitionUseCase useCase;

  @BeforeEach
  void setUp() {
    transitionRepo = Mockito.mock(OrderStateTransitionRepository.class);
    priceRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    appender = Mockito.mock(OrderTransitionAppender.class);
    useCase = new AppendOrderTransitionUseCase(
        transitionRepo, priceRepo, new OrderTransitionValidator(), appender);
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(Mockito.anyLong()))
        .thenReturn(Optional.empty());
    Mockito.when(transitionRepo.saveAndFlush(Mockito.any())).thenAnswer(inv -> {
      OrderStateTransition t = inv.getArgument(0);
      if (t.getId() == null) t.setId(1L);
      return t;
    });
    Mockito.when(appender.append(Mockito.any())).thenReturn(1L);
  }

  @Test
  void execute_appendsTransitionAndSnapshotOnFirstCall() {
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(12345L)
        .listPriceCents(10000L).taxCents(1000L).shippingCents(500L).totalCents(11500L)
        .currency("USD").capturedAt(LocalDateTime.now())
        .build();
    AppendOrderTransitionCommand cmd = new AppendOrderTransitionCommand(
        12345L, OrderState.PLACED, "order.placed", snapshot);

    long id = useCase.execute(cmd);

    assertThat(id).isEqualTo(1L);
    ArgumentCaptor<OrderPriceSnapshot> snapCaptor = ArgumentCaptor.forClass(OrderPriceSnapshot.class);
    Mockito.verify(priceRepo).saveAndFlush(snapCaptor.capture());
    assertThat(snapCaptor.getValue().getOrderUuid()).isEqualTo(12345L);
  }

  @Test
  void execute_genesisSnapshotRaceSwallowsDuplicateAndProceeds() {
    // Two concurrent genesis POSTs reach the snapshot insert with the same orderUuid + same
    // snapshot data (it comes from the same command body in a retry). The first saveAndFlush
    // wins; the second throws DataIntegrityViolationException on the snapshot's natural PK.
    // The use case must NOT propagate the exception (it would surface as a 500 to the client)
    // because the data is identical and the transition write can still proceed — the V005
    // unique index on order_state_transition will catch a true duplicate transition row.
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(99999L).listPriceCents(1000L).taxCents(0L)
        .shippingCents(0L).totalCents(1000L).currency("VND")
        .capturedAt(LocalDateTime.now()).build();
    Mockito.when(priceRepo.saveAndFlush(snapshot))
        .thenThrow(new DataIntegrityViolationException("duplicate snapshot PK"));
    // The follow-up findById confirms the winning thread's row is present — the snapshot is
    // safe to consider "already saved".
    Mockito.when(priceRepo.findById(99999L)).thenReturn(Optional.of(snapshot));

    long id = useCase.execute(new AppendOrderTransitionCommand(
        99999L, OrderState.PLACED, "order.placed", snapshot));

    assertThat(id).isEqualTo(1L);
    Mockito.verify(transitionRepo).saveAndFlush(Mockito.any());
  }

  @Test
  void execute_genesisSnapshotRaceRethrowsWhenWinnerNotFound() {
    // Edge case: DataIntegrityViolationException on saveAndFlush but findById returns empty.
    // This means the unique violation was on a different row, not the snapshot — a real error.
    // The use case must surface it.
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(88888L).listPriceCents(1000L).taxCents(0L)
        .shippingCents(0L).totalCents(1000L).currency("VND")
        .capturedAt(LocalDateTime.now()).build();
    Mockito.when(priceRepo.saveAndFlush(snapshot))
        .thenThrow(new DataIntegrityViolationException("some other constraint"));
    Mockito.when(priceRepo.findById(88888L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.execute(new AppendOrderTransitionCommand(
        88888L, OrderState.PLACED, "order.placed", snapshot)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void execute_skipsSnapshotOnSubsequentCalls() {
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(12345L).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now()).build();

    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(12345L))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(1L).orderUuid(12345L).fromState(null).toState("PLACED")
            .sagaStep("order.placed").eventId(100L)
            .createdAt(LocalDateTime.now()).build()));

    AppendOrderTransitionCommand cmd = new AppendOrderTransitionCommand(
        12345L, OrderState.PAID, "payment.captured", snapshot);
    long id = useCase.execute(cmd);

    assertThat(id).isEqualTo(1L);
    Mockito.verify(priceRepo, Mockito.never()).saveAndFlush(Mockito.any());
  }

  @Test
  void execute_duplicateSemanticTransitionReturnsExistingIdAndSkipsOutboxAppend() {
    // New pre-check (lines 50-54) returns the existing row's id when the latest transition
    // already carries the same (toState, sagaStep). The saveAndFlush path is never reached.
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(12345L).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now()).build();
    OrderStateTransition latest = OrderStateTransition.builder()
        .id(1L).orderUuid(12345L).fromState(null).toState("PLACED")
        .sagaStep("order.placed").eventId(100L)
        .createdAt(LocalDateTime.now()).build();
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(12345L))
        .thenReturn(Optional.of(latest));

    long id = useCase.execute(new AppendOrderTransitionCommand(
        12345L, OrderState.PLACED, "order.placed", snapshot));

    assertThat(id).isEqualTo(1L);
    Mockito.verify(transitionRepo, Mockito.never()).saveAndFlush(Mockito.any());
    Mockito.verify(appender, Mockito.never()).append(Mockito.any());
  }

  @Test
  void execute_throwsOnInvalidTransition() {
    Mockito.when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(12345L))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(1L).orderUuid(12345L).fromState(null).toState("PLACED")
            .sagaStep("order.placed").eventId(100L)
            .createdAt(LocalDateTime.now()).build()));
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(12345L).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now()).build();

    AppendOrderTransitionCommand cmd = new AppendOrderTransitionCommand(
        12345L, OrderState.SHIPPED, "shipment.dispatched", snapshot);

    assertThatThrownBy(() -> useCase.execute(cmd))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Invalid transition");
  }

  @Test
  void execute_throwsOnNullSagaStep() {
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(12345L).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now()).build();
    assertThatThrownBy(() -> new AppendOrderTransitionCommand(
        12345L, OrderState.PLACED, null, snapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sagaStep");
  }
}
