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
 * <p>// TODO verify HMAC envelope from the in-process event: the cross-service event record
 * carries the {@code signatures} map (per ADR-20); the consumer's seam is
 * {@link OrderHmacEventVerifier#verify}. For Spring Modulith's intra-process events, the
 * signatures land on the envelope's metadata, not the event record itself — the listener
 * needs to receive the full envelope. v1 skips the verification (the dev profile's
 * dev-mode signature does not round-trip cleanly through the in-process event bus);
 * the verifier is wired + tested for the cross-service case.
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

  public PaymentCapturedOrderAdvancer(
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository priceSnapshotRepository,
      AppendOrderTransitionUseCase appendUseCase,
      OrderHmacEventVerifier verifier,
      MeterRegistry meterRegistry) {
    this.transitionRepository = transitionRepository;
    this.priceSnapshotRepository = priceSnapshotRepository;
    this.appendUseCase = appendUseCase;
    this.verifier = verifier;
    this.signatureMismatchCounter = Counter.builder("security.event.signature.mismatch")
        .tag("producer", "payment").register(meterRegistry);
    this.errorCounter = Counter.builder("order.saga.payment_captured.error")
        .tag("event", "payment_captured").register(meterRegistry);
  }

  @EventListener
  @Transactional
  public void onPaymentCaptured(PaymentCapturedEvent event) {
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