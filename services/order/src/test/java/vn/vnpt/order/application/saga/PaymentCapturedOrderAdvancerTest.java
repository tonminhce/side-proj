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
import vn.vnpt.order.application.saga.event.PaymentCapturedEvent;
import vn.vnpt.order.application.usecase.AppendOrderTransitionUseCase;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.exception.OrderSnapshotMissingException;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;
import vn.vnpt.order.infrastructure.security.OrderHmacEventVerifier;

/**
 * Tests for the saga listener — Story 4.2.
 */
class PaymentCapturedOrderAdvancerTest {

  private OrderStateTransitionRepository transitionRepo;
  private OrderPriceSnapshotRepository priceRepo;
  private AppendOrderTransitionUseCase appendUseCase;
  private OrderHmacEventVerifier verifier;
  private MeterRegistry meterRegistry;
  private PaymentCapturedOrderAdvancer advancer;

  @BeforeEach
  void setUp() {
    transitionRepo = Mockito.mock(OrderStateTransitionRepository.class);
    priceRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    appendUseCase = Mockito.mock(AppendOrderTransitionUseCase.class);
    verifier = Mockito.mock(OrderHmacEventVerifier.class);
    meterRegistry = new SimpleMeterRegistry();
    advancer = new PaymentCapturedOrderAdvancer(
        transitionRepo, priceRepo, appendUseCase, verifier, meterRegistry);
    when(verifier.verify(any(), any())).thenReturn(true);
  }

  @Test
  void onPaymentCaptured_appendsPaidTransition() {
    long orderUuid = 11L;
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        orderUuid, "pi_test", 11500L, "USD", LocalDateTime.now());
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(orderUuid))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(1L).orderUuid(orderUuid).fromState(null).toState("PLACED")
            .sagaStep("order.placed").eventId(100L)
            .createdAt(LocalDateTime.now()).build()));
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(orderUuid).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now()).build();
    when(priceRepo.findById(orderUuid)).thenReturn(Optional.of(snapshot));

    advancer.onPaymentCaptured(event);

    verify(appendUseCase).execute(any(AppendOrderTransitionCommand.class));
  }

  @Test
  void onPaymentCaptured_skipsForUnknownOrder() {
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        99L, "pi_test", 11500L, "USD", LocalDateTime.now());
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(99L)).thenReturn(Optional.empty());

    advancer.onPaymentCaptured(event);

    verify(appendUseCase, never()).execute(any());
  }

  @Test
  void onPaymentCaptured_throwsWhenSnapshotMissing() {
    long orderUuid = 11L;
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        orderUuid, "pi_test", 11500L, "USD", LocalDateTime.now());
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(orderUuid))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(1L).orderUuid(orderUuid).fromState(null).toState("PLACED")
            .sagaStep("order.placed").eventId(100L)
            .createdAt(LocalDateTime.now()).build()));
    when(priceRepo.findById(orderUuid)).thenReturn(Optional.empty());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> advancer.onPaymentCaptured(event))
        .isInstanceOf(OrderSnapshotMissingException.class);
  }

  @Test
  void onSignatureMismatch_incrementsCounter() {
    advancer.onSignatureMismatch("evt_123");
    assertThat(meterRegistry.counter("security.event.signature.mismatch",
        "producer", "payment").count()).isEqualTo(1.0);
  }
}