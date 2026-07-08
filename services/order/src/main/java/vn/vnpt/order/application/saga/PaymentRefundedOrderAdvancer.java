package vn.vnpt.order.application.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.application.saga.event.PaymentRefundedEvent;
import vn.vnpt.order.application.usecase.AppendOrderTransitionUseCase;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Saga listener for {@code payment.refunded} — Story 3.5 follow-up #4. Advances
 * {@code PAID -> REFUNDED} on the matched order.
 *
 * <p>Mirrors {@link PaymentCapturedOrderAdvancer}. The price snapshot is re-fetched (not
 * re-created) because {@link AppendOrderTransitionCommand} requires a non-null snapshot
 * per the FR-31 trust-boundary contract (the snapshot is the immutable record of the
 * captured amount + tax + shipping; a refund doesn't change it but the contract enforces
 * that every transition carries the snapshot reference).
 *
 * <p>Idempotency: the underlying {@link AppendOrderTransitionUseCase} enforces append-only
 * semantics on the {@code OrderStateTransition} log (UNIQUE on
 * {@code (order_uuid, from_state, to_state, saga_step)}), so a duplicate refund event is a
 * no-op at the DB layer.
 */
@Component
public class PaymentRefundedOrderAdvancer {

  private static final Logger log = LoggerFactory.getLogger(PaymentRefundedOrderAdvancer.class);

  private final OrderStateTransitionRepository transitionRepository;
  private final OrderPriceSnapshotRepository priceSnapshotRepository;
  private final AppendOrderTransitionUseCase appendUseCase;
  private final Counter errorCounter;

  public PaymentRefundedOrderAdvancer(
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository priceSnapshotRepository,
      AppendOrderTransitionUseCase appendUseCase,
      MeterRegistry meterRegistry) {
    this.transitionRepository = transitionRepository;
    this.priceSnapshotRepository = priceSnapshotRepository;
    this.appendUseCase = appendUseCase;
    this.errorCounter = Counter.builder("order.saga.payment_refunded.error")
        .tag("event", "payment_refunded").register(meterRegistry);
  }

  @EventListener
  @Transactional
  public void onPaymentRefunded(PaymentRefundedEvent event) {
    try {
      var latest = transitionRepository.findFirstByOrderUuidOrderByIdDesc(event.orderUuid());
      if (latest.isEmpty()) {
        log.warn("payment.refunded for unknown orderUuid={} — skipping", event.orderUuid());
        return;
      }

      // Only advance from PAID; AMENDED/CANCELLED orders are terminal and refund-after-terminal
      // is a manual ops intervention (no auto-transition).
      OrderState current = OrderState.valueOf(latest.get().getToState());
      if (current != OrderState.PAID) {
        log.info("payment.refunded for orderUuid={} in state={} — not advancing (refund-after-{} is a manual ops flow)",
            event.orderUuid(), current, current);
        return;
      }

      OrderPriceSnapshot snapshot = priceSnapshotRepository.findById(event.orderUuid())
          .orElseThrow(() -> new IllegalStateException(
              "Order " + event.orderUuid() + " has PAID state but no price snapshot — "
                  + "data integrity violation; not advancing to REFUNDED"));

      appendUseCase.execute(new AppendOrderTransitionCommand(
          event.orderUuid(), OrderState.REFUNDED, "payment.refunded", snapshot));
    } catch (RuntimeException e) {
      errorCounter.increment();
      log.error("Saga advance to REFUNDED failed for orderUuid={}: {}",
          event.orderUuid(), e.getMessage());
      throw e;
    }
  }
}