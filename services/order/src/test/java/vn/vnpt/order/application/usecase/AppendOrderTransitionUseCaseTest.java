package vn.vnpt.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
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
    Mockito.when(transitionRepo.save(Mockito.any())).thenAnswer(inv -> {
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
    Mockito.verify(priceRepo).save(snapCaptor.capture());
    assertThat(snapCaptor.getValue().getOrderUuid()).isEqualTo(12345L);
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
    Mockito.verify(priceRepo, Mockito.never()).save(Mockito.any());
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