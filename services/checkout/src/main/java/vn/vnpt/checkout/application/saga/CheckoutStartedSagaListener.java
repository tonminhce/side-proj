package vn.vnpt.checkout.application.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import vn.vnpt.checkout.domain.event.CheckoutStartedEvent;

/**
 * Saga listener — Story 2.5 / FR-22, FR-23 (ADR-12 intra-Modulith).
 *
 * <p>{@code @TransactionalEventListener(phase = BEFORE_COMMIT)} means the saga runs inside the
 * producing transaction (the {@code StartCheckoutUseCase} that emitted the
 * {@link CheckoutStartedEvent}). The saga's {@code Order} INSERT + 3 transition-log rows + 3 outbox
 * appends are atomic with the {@code checkout.started} outbox row — ADR-04 atomicity. No second
 * polling tick is required to observe saga completion.
 *
 * <p>The {@code applicationEventPublisher.publishEvent(event)} in
 * {@code ModulithOutboxPublisher} is synchronous by default; the listener fires on the same thread.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CheckoutStartedSagaListener {

  private final OrderSagaOrchestrator orchestrator;

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void on(CheckoutStartedEvent event) {
    log.debug("Saga listener fired for checkoutUuid={}", event.getCheckoutUuid());
    orchestrator.handle(event);
  }
}