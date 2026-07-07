package vn.vnpt.checkout.application.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Saga recovery runner — Story 2.5 / FR-22, FR-23 (ADR-12 startup replay).
 *
 * <p>On boot, finds orders stuck in any in-flight status ({@code CREATED}, {@code STOCK_RESERVED},
 * {@code PAYMENT_PENDING}) for longer than the cutoff (default 5 minutes) and re-invokes the
 * matching saga step. Idempotency on {@code (orderUuid, saga_step_name)} prevents double-execution.
 *
 * <p>Bounded by {@code checkout.saga.recovery.max-per-boot} (default 100) so a wedged deploy with
 * 10k stuck orders doesn't have a 30-minute boot time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaRecoveryRunner implements ApplicationRunner {

  private final OrderRepository orderRepository;
  private final OrderStateTransitionRepository transitionRepository;
  private final OrderSagaOrchestrator orchestrator;
  private final MeterRegistry meterRegistry;

  @Value("${checkout.saga.recovery.cutoff-minutes:5}")
  private long cutoffMinutes;

  @Value("${checkout.saga.recovery.max-per-boot:100}")
  private int maxPerBoot;

  @Override
  public void run(ApplicationArguments args) {
    LocalDateTime cutoff = LocalDateTime.now().minus(Duration.ofMinutes(cutoffMinutes));
    List<Order> stuck = orderRepository.findStuckOrders(
        List.copyOf(OrderStatus.IN_FLIGHT), cutoff);

    if (stuck.size() > maxPerBoot) {
      log.warn("Saga recovery: {} stuck orders found, processing first {} only",
          stuck.size(), maxPerBoot);
      stuck = stuck.subList(0, maxPerBoot);
    }

    if (stuck.isEmpty()) {
      log.info("Saga recovery: no stuck orders (cutoff={}min)", cutoffMinutes);
      return;
    }

    log.info("Saga recovery: processing {} stuck orders (cutoff={}min)",
        stuck.size(), cutoffMinutes);
    int processed = 0;
    for (Order order : stuck) {
      OrderStateTransition last = transitionRepository
          .findFirstByOrderUuidOrderByCreatedAtDesc(order.getUuid());
      if (last == null) {
        log.warn("Saga recovery: order={} has no transition rows, skipping", order.getUuid());
        continue;
      }
      try {
        switch (last.getSagaStep()) {
          case OrderSagaOrchestrator.STEP_CART_SUBMIT -> orchestrator.resumeFromCreated(order);
          case OrderSagaOrchestrator.STEP_STOCK_RESERVE -> orchestrator.resumeFromStockReserved(order);
          case OrderSagaOrchestrator.STEP_PAYMENT_INTENT_CREATED -> orchestrator.resumeFromPaymentPending(order);
          default -> log.warn("Saga recovery: unknown saga step '{}' for order={}",
              last.getSagaStep(), order.getUuid());
        }
        processed++;
      } catch (Exception e) {
        log.warn("Saga recovery: failed to resume order={} step={}: {}",
            order.getUuid(), last.getSagaStep(), e.getMessage(), e);
      }
    }
    Counter.builder("saga.stuck_orders.processed")
        .register(meterRegistry)
        .increment(processed);
    log.info("Saga recovery: processed {} orders", processed);
  }
}