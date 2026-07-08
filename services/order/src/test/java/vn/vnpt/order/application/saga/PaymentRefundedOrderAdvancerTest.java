package vn.vnpt.order.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.application.saga.event.PaymentRefundedEvent;
import vn.vnpt.order.application.usecase.AppendOrderTransitionUseCase;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Tests for the saga listener — Story 3.5 follow-up #4.
 */
class PaymentRefundedOrderAdvancerTest {

  private OrderStateTransitionRepository transitionRepo;
  private OrderPriceSnapshotRepository priceRepo;
  private AppendOrderTransitionUseCase appendUseCase;
  private MeterRegistry meterRegistry;
  private PaymentRefundedOrderAdvancer advancer;

  @BeforeEach
  void setUp() {
    transitionRepo = Mockito.mock(OrderStateTransitionRepository.class);
    priceRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    appendUseCase = Mockito.mock(AppendOrderTransitionUseCase.class);
    meterRegistry = new SimpleMeterRegistry();
    advancer = new PaymentRefundedOrderAdvancer(
        transitionRepo, priceRepo, appendUseCase, meterRegistry);
  }

  @Test
  void onPaymentRefunded_appendsRefundedTransitionWhenCurrentIsPaid() {
    long orderUuid = 11L;
    PaymentRefundedEvent event = new PaymentRefundedEvent(
        orderUuid, "pi_test", 50000L, "VND", LocalDateTime.now());
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(orderUuid))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(2L).orderUuid(orderUuid).fromState("PLACED").toState("PAID")
            .sagaStep("payment.captured").eventId(101L)
            .createdAt(LocalDateTime.now()).build()));
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(orderUuid).listPriceCents(40000L).taxCents(5000L)
        .shippingCents(5000L).totalCents(50000L).currency("VND")
        .capturedAt(LocalDateTime.now()).build();
    when(priceRepo.findById(orderUuid)).thenReturn(Optional.of(snapshot));

    advancer.onPaymentRefunded(event);

    verify(appendUseCase).execute(any(AppendOrderTransitionCommand.class));
    assertThat(meterRegistry.counter("order.saga.payment_refunded.error",
        "event", "payment_refunded").count()).isEqualTo(0.0);
  }

  @Test
  void onPaymentRefunded_skipsForUnknownOrder() {
    PaymentRefundedEvent event = new PaymentRefundedEvent(
        99L, "pi_test", 50000L, "VND", LocalDateTime.now());
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(99L)).thenReturn(Optional.empty());

    advancer.onPaymentRefunded(event);

    verify(appendUseCase, never()).execute(any());
  }

  @Test
  void onPaymentRefunded_skipsWhenCurrentStateIsNotPaid() {
    long orderUuid = 11L;
    PaymentRefundedEvent event = new PaymentRefundedEvent(
        orderUuid, "pi_test", 50000L, "VND", LocalDateTime.now());
    // Order is in SHIPPED state — refund-after-terminal is a manual ops flow, no auto-advance.
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(orderUuid))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(5L).orderUuid(orderUuid).fromState("PACKED").toState("SHIPPED")
            .sagaStep("shipment.dispatched").eventId(110L)
            .createdAt(LocalDateTime.now()).build()));

    advancer.onPaymentRefunded(event);

    verify(appendUseCase, never()).execute(any());
  }
}