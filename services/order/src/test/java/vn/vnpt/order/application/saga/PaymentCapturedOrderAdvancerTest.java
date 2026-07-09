package vn.vnpt.order.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import vn.vnpt.order.application.saga.event.SignedPaymentCapturedEvent;
import vn.vnpt.order.application.usecase.AccrueLoyaltyPointsUseCase;
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
  private AccrueLoyaltyPointsUseCase accrueLoyaltyUseCase;
  private MeterRegistry meterRegistry;
  private PaymentCapturedOrderAdvancer advancer;

  @BeforeEach
  void setUp() {
    transitionRepo = Mockito.mock(OrderStateTransitionRepository.class);
    priceRepo = Mockito.mock(OrderPriceSnapshotRepository.class);
    appendUseCase = Mockito.mock(AppendOrderTransitionUseCase.class);
    verifier = Mockito.mock(OrderHmacEventVerifier.class);
    accrueLoyaltyUseCase = Mockito.mock(AccrueLoyaltyPointsUseCase.class);
    meterRegistry = new SimpleMeterRegistry();
    advancer = new PaymentCapturedOrderAdvancer(
        transitionRepo, priceRepo, appendUseCase, verifier, accrueLoyaltyUseCase, meterRegistry);
    when(verifier.verifyPaymentEventEnvelope(anyLong(), any(), any(), anyLong(), any(), any()))
        .thenReturn(true);
    when(accrueLoyaltyUseCase.execute(anyLong(), anyLong(), anyLong())).thenReturn(0);
  }

  @Test
  void onPaymentCaptured_appendsPaidTransitionForValidSignedEnvelope() {
    long orderUuid = 11L;
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        orderUuid, "pi_test", 11500L, "USD", LocalDateTime.now());
    SignedPaymentCapturedEvent signed = signed(event);
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

    advancer.onPaymentCaptured(signed);

    verify(verifier).verifyPaymentEventEnvelope(
        signed.eventId(), "payment.captured", signed.aggregateType(), signed.aggregateId(),
        signed.payloadJson(), signed.signatures());
    verify(appendUseCase).execute(any(AppendOrderTransitionCommand.class));
  }

  @Test
  void onPaymentCaptured_skipsUnsignedBareEvent() {
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        99L, "pi_test", 11500L, "USD", LocalDateTime.now());

    advancer.onPaymentCaptured(event);

    assertThat(meterRegistry.counter("security.event.signature.mismatch",
        "producer", "payment").count()).isEqualTo(1.0);
    verify(appendUseCase, never()).execute(any());
  }

  @Test
  void onPaymentCaptured_skipsForUnknownOrder() {
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        99L, "pi_test", 11500L, "USD", LocalDateTime.now());
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(99L)).thenReturn(Optional.empty());

    advancer.onPaymentCaptured(signed(event));

    verify(appendUseCase, never()).execute(any());
  }

  @Test
  void onPaymentCaptured_skipsWhenSignatureInvalid() {
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        99L, "pi_test", 11500L, "USD", LocalDateTime.now());
    SignedPaymentCapturedEvent signed = signed(event);
    when(verifier.verifyPaymentEventEnvelope(
        signed.eventId(), "payment.captured", signed.aggregateType(), signed.aggregateId(),
        signed.payloadJson(), signed.signatures())).thenReturn(false);

    advancer.onPaymentCaptured(signed);

    assertThat(meterRegistry.counter("security.event.signature.mismatch",
        "producer", "payment").count()).isEqualTo(1.0);
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

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> advancer.onPaymentCaptured(signed(event)))
        .isInstanceOf(OrderSnapshotMissingException.class);
  }

  /** Move A — snapshot.userId present → saga passes it as customerId to accrual. */
  @Test
  void onPaymentCaptured_resolvesCustomerIdFromSnapshotUserId() {
    long orderUuid = 11L;
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        orderUuid, "pi_test", 11500L, "USD", LocalDateTime.now());
    SignedPaymentCapturedEvent signed = signed(event);
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(orderUuid))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(1L).orderUuid(orderUuid).fromState(null).toState("PLACED")
            .sagaStep("order.placed").eventId(100L)
            .createdAt(LocalDateTime.now()).build()));
    OrderPriceSnapshot snapshot = OrderPriceSnapshot.builder()
        .orderUuid(orderUuid).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now())
        .userId(99L)  // Move A — the saga sources customerId from snapshot.userId
        .build();
    when(priceRepo.findById(orderUuid)).thenReturn(Optional.of(snapshot));

    advancer.onPaymentCaptured(signed);

    verify(accrueLoyaltyUseCase).execute(orderUuid, 99L, 11500L);
  }

  /** Move A — legacy snapshot without userId → saga skips accrual (no use-case call). */
  @Test
  void onPaymentCaptured_skipsAccrualWhenSnapshotHasNoUserId() {
    long orderUuid = 11L;
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        orderUuid, "pi_test", 11500L, "USD", LocalDateTime.now());
    SignedPaymentCapturedEvent signed = signed(event);
    when(transitionRepo.findFirstByOrderUuidOrderByIdDesc(orderUuid))
        .thenReturn(Optional.of(OrderStateTransition.builder()
            .id(1L).orderUuid(orderUuid).fromState(null).toState("PLACED")
            .sagaStep("order.placed").eventId(100L)
            .createdAt(LocalDateTime.now()).build()));
    OrderPriceSnapshot legacySnapshot = OrderPriceSnapshot.builder()
        .orderUuid(orderUuid).listPriceCents(10000L).taxCents(1000L)
        .shippingCents(500L).totalCents(11500L).currency("USD")
        .capturedAt(LocalDateTime.now())
        .userId(null)  // legacy snapshot — pre-V007
        .build();
    when(priceRepo.findById(orderUuid)).thenReturn(Optional.of(legacySnapshot));

    advancer.onPaymentCaptured(signed);

    // PAID transition still appended.
    verify(appendUseCase).execute(any(AppendOrderTransitionCommand.class));
    // Loyalty accrual SKIPPED — no use-case call.
    verify(accrueLoyaltyUseCase, never()).execute(anyLong(), anyLong(), anyLong());
  }

  @Test
  void onSignatureMismatch_incrementsCounter() {
    advancer.onSignatureMismatch("evt_123");
    assertThat(meterRegistry.counter("security.event.signature.mismatch",
        "producer", "payment").count()).isEqualTo(1.0);
  }

  private static SignedPaymentCapturedEvent signed(PaymentCapturedEvent event) {
    return new SignedPaymentCapturedEvent(
        7001L,
        "Payment",
        123456L,
        "{\"orderUuid\":" + event.orderUuid() + "}",
        java.util.Map.of("service", "payment", "hmac_sha256", "sig", "key_id", "v1"),
        event);
  }
}
