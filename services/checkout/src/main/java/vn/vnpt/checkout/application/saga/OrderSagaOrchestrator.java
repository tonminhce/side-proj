package vn.vnpt.checkout.application.saga;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.domain.event.CheckoutStartedEvent;
import vn.vnpt.checkout.domain.exception.InsufficientStockDomainException;
import vn.vnpt.checkout.infrastructure.inventory.JavaDirectInventoryReservationAdapter;
import vn.vnpt.checkout.infrastructure.outbox.OrderEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Saga orchestrator — Story 2.5 / FR-22, FR-23 (ADR-12 intra-Modulith).
 *
 * <p>Drives the {@code (none) → CREATED → STOCK_RESERVED → PAYMENT_PENDING} sequence on an
 * {@link Order} aggregate. The listener ({@link CheckoutStartedSagaListener}) runs the orchestrator
 * inside the producing transaction ({@code @TransactionalEventListener(phase = BEFORE_COMMIT)}) so
 * the saga's state writes + outbox appends are atomic with the {@code checkout.started} row.
 *
 * <p>Idempotency: the orchestrator checks {@code findByCheckoutUuid} first; a second invocation
 * for the same {@code checkoutUuid} is a no-op (the existing Order row is the dedup mark —
 * NFR-IDEM-1 satisfied without a separate processed_event insert).
 *
 * <p>Crash-recovery: {@link OrderSagaRecoveryRunner} re-invokes {@code resumeFromCreated} /
 * {@code resumeFromStockReserved} / {@code resumeFromPaymentPending} on boot for orders stuck in
 * in-flight states.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaOrchestrator {

  /** ADR-11 — saga step names are stable tags paired with orderUuid for idempotency keys. */
  public static final String STEP_CART_SUBMIT = "cart.submit";

  public static final String STEP_STOCK_RESERVE = "stock.reserve";
  public static final String STEP_PAYMENT_INTENT_CREATED = "payment.intent.created";

  /** Failure reason values (free-text for now; later stories can promote to enum). */
  public static final String FAILURE_REASON_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

  private final OrderRepository orderRepository;
  private final OrderStateTransitionRepository transitionRepository;
  private final OrderEventPublisher orderEventPublisher;
  private final JavaDirectInventoryReservationAdapter inventoryAdapter;
  private final MeterRegistry meterRegistry;

  /**
   * Main saga handler — runs inside the producing transaction
   * ({@code @TransactionalEventListener(phase = BEFORE_COMMIT)}).
   */
  @Transactional
  public void handle(CheckoutStartedEvent event) {
    if (event.getCheckoutUuid() == null) {
      throw new IllegalArgumentException("checkoutUuid is required");
    }
    // Saga idempotency — already-processed checkout → no-op.
    Optional<Order> existing = orderRepository.findByCheckoutUuid(event.getCheckoutUuid());
    if (existing.isPresent()) {
      log.debug("Saga no-op: order already exists for checkoutUuid={}", event.getCheckoutUuid());
      return;
    }

    Order order = newOrder(event);
    Order persisted = orderRepository.save(order);
    appendTransition(persisted.getUuid(), null, OrderStatus.CREATED, STEP_CART_SUBMIT, null);
    orderEventPublisher.publishOrderCreated(persisted);
    incrementTransition("none", OrderStatus.CREATED.name());

    // ── step 2: stock.reserve ──
    String idemKey = persisted.getUuid() + ":" + STEP_STOCK_RESERVE;
    JavaDirectInventoryReservationAdapter.Result reserveResult;
    try {
      reserveResult = inventoryAdapter.reserve(persisted, event.getCartLines(), idemKey);
    } catch (InsufficientStockDomainException ex) {
      handleInsufficientStock(persisted, ex);
      return;
    }
    persisted.transitionTo(OrderStatus.STOCK_RESERVED, STEP_STOCK_RESERVE, null);
    appendTransition(persisted.getUuid(), OrderStatus.CREATED, OrderStatus.STOCK_RESERVED,
        STEP_STOCK_RESERVE, null);
    orderEventPublisher.publishOrderStockReserved(persisted, reserveResult.reservationUuid());
    incrementTransition(OrderStatus.CREATED.name(), OrderStatus.STOCK_RESERVED.name());

    // ── step 3: payment.intent.created ──
    persisted.transitionTo(OrderStatus.PAYMENT_PENDING, STEP_PAYMENT_INTENT_CREATED, null);
    appendTransition(persisted.getUuid(), OrderStatus.STOCK_RESERVED, OrderStatus.PAYMENT_PENDING,
        STEP_PAYMENT_INTENT_CREATED, null);
    orderEventPublisher.publishOrderPaymentPending(persisted);
    incrementTransition(OrderStatus.STOCK_RESERVED.name(), OrderStatus.PAYMENT_PENDING.name());
  }

  /** Resume helper — invoked by the recovery runner on startup. Idempotent on status. */
  @Transactional
  public void resumeFromCreated(Order order) {
    if (order.getStatus() != OrderStatus.CREATED) {
      log.debug("resumeFromCreated: skipping order={} status={}", order.getUuid(), order.getStatus());
      return;
    }
    String idemKey = order.getUuid() + ":" + STEP_STOCK_RESERVE;
    JavaDirectInventoryReservationAdapter.Result reserveResult;
    // cartLines are not in the event replay path — they live on the Checkout aggregate and the
    // saga only persists order refs. A future story adds a cart-line snapshot so the recovery
    // runner can replay stock.reserve without re-fetching from Checkout. Today the adapter
    // rejects empty cartLines; the IllegalArgumentException propagates to the recovery runner,
    // which logs + leaves the order in CREATED for admin intervention (do NOT mark FAILED —
    // "cartLines missing" is not the same as "insufficient stock").
    reserveResult = inventoryAdapter.reserve(order, java.util.List.of(), idemKey);
    order.transitionTo(OrderStatus.STOCK_RESERVED, STEP_STOCK_RESERVE, null);
    appendTransition(order.getUuid(), OrderStatus.CREATED, OrderStatus.STOCK_RESERVED,
        STEP_STOCK_RESERVE, null);
    orderEventPublisher.publishOrderStockReserved(order, reserveResult.reservationUuid());
    incrementTransition(OrderStatus.CREATED.name(), OrderStatus.STOCK_RESERVED.name());
    order.transitionTo(OrderStatus.PAYMENT_PENDING, STEP_PAYMENT_INTENT_CREATED, null);
    appendTransition(order.getUuid(), OrderStatus.STOCK_RESERVED, OrderStatus.PAYMENT_PENDING,
        STEP_PAYMENT_INTENT_CREATED, null);
    orderEventPublisher.publishOrderPaymentPending(order);
    incrementTransition(OrderStatus.STOCK_RESERVED.name(), OrderStatus.PAYMENT_PENDING.name());
  }

  @Transactional
  public void resumeFromStockReserved(Order order) {
    if (order.getStatus() != OrderStatus.STOCK_RESERVED) {
      log.debug("resumeFromStockReserved: skipping order={} status={}",
          order.getUuid(), order.getStatus());
      return;
    }
    order.transitionTo(OrderStatus.PAYMENT_PENDING, STEP_PAYMENT_INTENT_CREATED, null);
    appendTransition(order.getUuid(), OrderStatus.STOCK_RESERVED, OrderStatus.PAYMENT_PENDING,
        STEP_PAYMENT_INTENT_CREATED, null);
    orderEventPublisher.publishOrderPaymentPending(order);
    incrementTransition(OrderStatus.STOCK_RESERVED.name(), OrderStatus.PAYMENT_PENDING.name());
  }

  @Transactional
  public void resumeFromPaymentPending(Order order) {
    // PAYMENT_PENDING → terminal transitions land in Epic 3 (Stripe webhook). The recovery runner
    // leaves a PAYMENT_PENDING order alone; the saga has done its job.
    log.debug("resumeFromPaymentPending: nothing to do for order={}", order.getUuid());
  }

  /**
   * Build a new {@link Order} aggregate from the {@link CheckoutStartedEvent}. The aggregate's
   * uuid + version are assigned by {@code BaseEntity.@PrePersist} (the saga does NOT pre-set
   * them — Story 2.4 C1 fix).
   */
  private Order newOrder(CheckoutStartedEvent event) {
    return Order.builder()
        .tenantId(event.getTenantId() == null ? "default" : event.getTenantId())
        .cartUuid(event.getCartUuid())
        .checkoutUuid(event.getCheckoutUuid())
        .paymentIntentId(event.getPaymentIntentId())
        .shippingAddress(event.getShippingAddress())
        .status(OrderStatus.CREATED)
        .build();
  }

  /**
   * Failure handling — runs in the SAME transaction as the orchestrator (no REQUIRES_NEW). The
   * Order entity is in the outer persistence context, so {@code transitionTo(FAILED)} mutates the
   * entity that will be flushed on commit; the Order row INSERTs as FAILED, the transition row
   * appends in the same tx, and {@code OrderFailedEvent} lands in the outbox alongside
   * {@code OrderCreatedEvent}. Downstream consumers see both events; the order's final state
   * (FAILED) is consistent with the transition log.
   */
  void handleInsufficientStock(Order order, Throwable cause) {
    String reason = FAILURE_REASON_INSUFFICIENT_STOCK;
    log.warn("Saga failure on order={} reason={}: {}", order.getUuid(), reason, cause.getMessage());
    order.transitionTo(OrderStatus.FAILED, STEP_STOCK_RESERVE, reason);
    appendTransition(order.getUuid(), OrderStatus.CREATED, OrderStatus.FAILED,
        STEP_STOCK_RESERVE, reason);
    orderEventPublisher.publishOrderFailed(order);
    incrementTransition(OrderStatus.CREATED.name(), OrderStatus.FAILED.name());
  }

  private void appendTransition(Long orderUuid, OrderStatus from, OrderStatus to, String sagaStep,
      String failureReason) {
    transitionRepository.save(
        new OrderStateTransition(orderUuid, from, to, sagaStep, failureReason));
  }

  private void incrementTransition(String from, String to) {
    Counter.builder("saga.transition.count")
        .tag("from", from == null ? "none" : from)
        .tag("to", to == null ? "none" : to)
        .register(meterRegistry)
        .increment();
  }
}