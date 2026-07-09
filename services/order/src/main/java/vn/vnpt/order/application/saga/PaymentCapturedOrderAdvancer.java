package vn.vnpt.order.application.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.application.saga.event.PaymentCapturedEvent;
import vn.vnpt.order.application.saga.event.SignedPaymentCapturedEvent;
import vn.vnpt.order.application.usecase.AccrueLoyaltyPointsUseCase;
import vn.vnpt.order.application.usecase.AppendOrderTransitionUseCase;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.exception.OrderSnapshotMissingException;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;
import vn.vnpt.order.infrastructure.security.OrderHmacEventVerifier;

/**
 * Saga listener for {@code payment.captured} — Story 4.2 / FR-32 / ADR-12 (intra-process).
 * Advances {@code PLACED → PAID} on the matched order.
 *
 * <p>The state transition only runs for signed envelopes. A bare {@link PaymentCapturedEvent}
 * is treated as unsigned and skipped so test/debug paths cannot bypass ADR-20 verification.
 */
@Component
public class PaymentCapturedOrderAdvancer {

  private static final Logger log = LoggerFactory.getLogger(PaymentCapturedOrderAdvancer.class);

  private final OrderStateTransitionRepository transitionRepository;
  private final OrderPriceSnapshotRepository priceSnapshotRepository;
  private final AppendOrderTransitionUseCase appendUseCase;
  private final OrderHmacEventVerifier verifier;
  private final Counter signatureMismatchCounter;
  private final Counter errorCounter;
  // Story 5.6 / FR-50 — loyalty accrual on PAID. customerId is unknown in v1 (the snapshot
  // carries no userId); the accrual call below is a no-op until the OrderPriceSnapshot captures
  // a userId (deferred per deferred-issues.md). // ponytail: orderUuid-only accrue, swap to
  // (orderUuid, customerId, totalCents) once snapshot.userId exists.
  private final AccrueLoyaltyPointsUseCase accrueLoyaltyPointsUseCase;

  public PaymentCapturedOrderAdvancer(
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository priceSnapshotRepository,
      AppendOrderTransitionUseCase appendUseCase,
      OrderHmacEventVerifier verifier,
      AccrueLoyaltyPointsUseCase accrueLoyaltyPointsUseCase,
      MeterRegistry meterRegistry) {
    this.transitionRepository = transitionRepository;
    this.priceSnapshotRepository = priceSnapshotRepository;
    this.appendUseCase = appendUseCase;
    this.verifier = verifier;
    this.accrueLoyaltyPointsUseCase = accrueLoyaltyPointsUseCase;
    this.signatureMismatchCounter = Counter.builder("security.event.signature.mismatch")
        .tag("producer", "payment").register(meterRegistry);
    this.errorCounter = Counter.builder("order.saga.payment_captured.error")
        .tag("event", "payment_captured").register(meterRegistry);
  }

  @EventListener
  @Transactional
  public void onPaymentCaptured(PaymentCapturedEvent event) {
    onSignatureMismatch("unsigned:payment.captured:" + event.orderUuid());
  }

  @EventListener
  @Transactional
  public void onPaymentCaptured(SignedPaymentCapturedEvent signedEvent) {
    if (!verifier.verifyPaymentEventEnvelope(
        signedEvent.eventId(),
        "payment.captured",
        signedEvent.aggregateType(),
        signedEvent.aggregateId(),
        signedEvent.payloadJson(),
        signedEvent.signatures())) {
      onSignatureMismatch(String.valueOf(signedEvent.eventId()));
      return;
    }
    advance(signedEvent.payload());
  }

  private void advance(PaymentCapturedEvent event) {
    try {
      // Verify the order was placed (PLACED is the genesis state).
      var latest = transitionRepository.findFirstByOrderUuidOrderByIdDesc(event.orderUuid());
      if (latest.isEmpty()) {
        log.warn("payment.captured for unknown orderUuid={} — skipping", event.orderUuid());
        return;
      }

      // Look up the existing immutable price snapshot.
      OrderPriceSnapshot snapshot = priceSnapshotRepository.findById(event.orderUuid())
          .orElseThrow(() -> new OrderSnapshotMissingException(event.orderUuid()));

      // Append the PLACED → PAID transition.
      appendUseCase.execute(new AppendOrderTransitionCommand(
          event.orderUuid(), OrderState.PAID, "payment.captured", snapshot));

      // Story 5.6 / FR-50 — accrue loyalty points. v1 has no userId on the snapshot so
      // customerId is 0 and the use case is a no-op (the accrual endpoint is the v1 entry
      // point). When snapshot.userId lands, replace 0 with the resolved customerId.
      try {
        int accrued = accrueLoyaltyPointsUseCase.execute(event.orderUuid(), 0L, snapshot.getTotalCents());
        if (accrued == 0) {
          log.debug("Loyalty accrual skipped for orderUuid={} (no customerId mapping in v1)",
              event.orderUuid());
        }
      } catch (RuntimeException loyaltyEx) {
        // Loyalty is best-effort post-advance; a failure here must NOT roll back the PAID transition.
        log.warn("Loyalty accrual failed for orderUuid={} (PAID transition preserved): {}",
            event.orderUuid(), loyaltyEx.getMessage());
      }
    } catch (RuntimeException e) {
      errorCounter.increment();
      log.error("Saga advance to PAID failed for orderUuid={}: {}",
          event.orderUuid(), e.getMessage());
      throw e;
    }
  }

  public void onSignatureMismatch(String eventId) {
    signatureMismatchCounter.increment();
    log.warn("HMAC signature mismatch on producer=payment event_id={}", eventId);
  }
}
