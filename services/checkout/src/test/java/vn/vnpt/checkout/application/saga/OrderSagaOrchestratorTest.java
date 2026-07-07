package vn.vnpt.checkout.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.event.CheckoutStartedEvent;
import vn.vnpt.checkout.domain.exception.InsufficientStockDomainException;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.checkout.infrastructure.inventory.JavaDirectInventoryReservationAdapter;
import vn.vnpt.checkout.infrastructure.outbox.OrderEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Pure-mockito unit tests for {@link OrderSagaOrchestrator} — Story 2.5 / FR-22.
 *
 * <p>Three paths exercised: happy-path, duplicate-delivery no-op, insufficient-stock failure
 * branch. The orchestrator is constructed by hand (no Spring context) so the test runs in
 * milliseconds.
 */
class OrderSagaOrchestratorTest {

  private OrderRepository orderRepository;
  private OrderStateTransitionRepository transitionRepository;
  private OrderEventPublisher orderEventPublisher;
  private JavaDirectInventoryReservationAdapter inventoryAdapter;
  private OrderSagaOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    transitionRepository = org.mockito.Mockito.mock(OrderStateTransitionRepository.class);
    orderEventPublisher = org.mockito.Mockito.mock(OrderEventPublisher.class);
    inventoryAdapter = org.mockito.Mockito.mock(JavaDirectInventoryReservationAdapter.class);
    orchestrator = new OrderSagaOrchestrator(orderRepository, transitionRepository,
        orderEventPublisher, inventoryAdapter, new SimpleMeterRegistry());

    // Persist returns the order unchanged (with uuid assigned — we synthesize a uuid).
    when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
      Order arg = inv.getArgument(0);
      arg.setUuid(42L);
      arg.setVersion(0L);
      // Mirror uuid → eventId so append-only tests pass.
      return arg;
    });
    when(transitionRepository.save(any(OrderStateTransition.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void happyPath_persistsOrder_appends3Transitions_publishes3OutboxEvents() {
    CheckoutStartedEvent event = newCheckoutStartedEvent();
    when(orderRepository.findByCheckoutUuid(event.getCheckoutUuid())).thenReturn(Optional.empty());
    when(inventoryAdapter.reserve(any(Order.class), anyList(), anyString()))
        .thenReturn(new JavaDirectInventoryReservationAdapter.Result(99L, 7L, Instant.now()));

    orchestrator.handle(event);

    // Order saved once (initial insert). Status at insert time is CREATED. Captured reference
    // shows the FINAL status because the orchestrator mutates the same Order across transitions
    // — we therefore verify the transition-log rows (which capture each status at append time).
    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(orderRepository).save(orderCaptor.capture());
    assertThat(orderCaptor.getValue().getCartUuid()).isEqualTo(event.getCartUuid());
    assertThat(orderCaptor.getValue().getCheckoutUuid()).isEqualTo(event.getCheckoutUuid());

    // Three transition-log rows appended (one per step) — captured at append time.
    ArgumentCaptor<OrderStateTransition> txCaptor = ArgumentCaptor.forClass(OrderStateTransition.class);
    verify(transitionRepository, times(3)).save(txCaptor.capture());
    List<OrderStateTransition> txs = txCaptor.getAllValues();
    assertThat(txs.get(0).getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_CART_SUBMIT);
    assertThat(txs.get(0).getFromState()).isNull();
    assertThat(txs.get(0).getToState()).isEqualTo(OrderStatus.CREATED);
    assertThat(txs.get(1).getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_STOCK_RESERVE);
    assertThat(txs.get(1).getFromState()).isEqualTo(OrderStatus.CREATED);
    assertThat(txs.get(1).getToState()).isEqualTo(OrderStatus.STOCK_RESERVED);
    assertThat(txs.get(2).getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_PAYMENT_INTENT_CREATED);
    assertThat(txs.get(2).getFromState()).isEqualTo(OrderStatus.STOCK_RESERVED);
    assertThat(txs.get(2).getToState()).isEqualTo(OrderStatus.PAYMENT_PENDING);

    // Three outbox events published.
    verify(orderEventPublisher).publishOrderCreated(any(Order.class));
    verify(orderEventPublisher).publishOrderStockReserved(any(Order.class), eq(99L));
    verify(orderEventPublisher).publishOrderPaymentPending(any(Order.class));
  }

  @Test
  void duplicateDelivery_isNoOp() {
    CheckoutStartedEvent event = newCheckoutStartedEvent();
    Order existing = Order.builder()
        .tenantId("default").cartUuid(event.getCartUuid())
        .checkoutUuid(event.getCheckoutUuid()).status(OrderStatus.PAYMENT_PENDING).version(1L)
        .build();
    existing.setUuid(42L);
    when(orderRepository.findByCheckoutUuid(event.getCheckoutUuid())).thenReturn(Optional.of(existing));

    orchestrator.handle(event);

    // No new order, no transition rows, no outbox events.
    verify(orderRepository, never()).save(any(Order.class));
    verify(transitionRepository, never()).save(any(OrderStateTransition.class));
    verify(orderEventPublisher, never()).publishOrderCreated(any(Order.class));
    verify(orderEventPublisher, never()).publishOrderStockReserved(any(Order.class), anyLong());
    verify(orderEventPublisher, never()).publishOrderPaymentPending(any(Order.class));
  }

  @Test
  void insufficientStock_marksOrderFailed_emitsFailedEvent() {
    CheckoutStartedEvent event = newCheckoutStartedEvent();
    when(orderRepository.findByCheckoutUuid(event.getCheckoutUuid())).thenReturn(Optional.empty());
    when(inventoryAdapter.reserve(any(Order.class), anyList(), anyString()))
        .thenThrow(new InsufficientStockDomainException("insufficient stock for variant=1001"));

    orchestrator.handle(event);

    // The order is saved once (CREATED insert); handleInsufficientStock mutates the entity in
    // place via transitionTo(FAILED) — same-tx dirty-update on flush, no second save() call.
    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(orderRepository, times(1)).save(orderCaptor.capture());

    // The transition log captures the failure transition (CREATED → FAILED).
    ArgumentCaptor<OrderStateTransition> txCaptor = ArgumentCaptor.forClass(OrderStateTransition.class);
    verify(transitionRepository, atLeastOnce()).save(txCaptor.capture());
    List<OrderStateTransition> txs = txCaptor.getAllValues();
    OrderStateTransition failTx = txs.stream()
        .filter(t -> t.getToState() == OrderStatus.FAILED)
        .findFirst().orElseThrow();
    assertThat(failTx.getFromState()).isEqualTo(OrderStatus.CREATED);
    assertThat(failTx.getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_STOCK_RESERVE);
    assertThat(failTx.getFailureReason()).isEqualTo(
        OrderSagaOrchestrator.FAILURE_REASON_INSUFFICIENT_STOCK);

    // The outbox event was published with the failed order's state.
    ArgumentCaptor<Order> failedOrderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(orderEventPublisher).publishOrderFailed(failedOrderCaptor.capture());
    Order failed = failedOrderCaptor.getValue();
    assertThat(failed.getFailureReason()).isEqualTo(OrderSagaOrchestrator.FAILURE_REASON_INSUFFICIENT_STOCK);
    assertThat(failed.getFailureSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_STOCK_RESERVE);

    // No stock_reserved or payment_pending event published.
    verify(orderEventPublisher, never()).publishOrderStockReserved(any(Order.class), anyLong());
    verify(orderEventPublisher, never()).publishOrderPaymentPending(any(Order.class));
  }

  /** AC #3 — the saga copies paymentIntentId from the event onto the Order row. */
  @Test
  void happyPath_copiesPaymentIntentIdFromEventOntoOrder() {
    CheckoutStartedEvent event = newCheckoutStartedEvent();
    when(orderRepository.findByCheckoutUuid(event.getCheckoutUuid())).thenReturn(Optional.empty());
    when(inventoryAdapter.reserve(any(Order.class), anyList(), anyString()))
        .thenReturn(new JavaDirectInventoryReservationAdapter.Result(99L, 7L, Instant.now()));

    orchestrator.handle(event);

    ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(orderRepository).save(orderCaptor.capture());
    assertThat(orderCaptor.getValue().getPaymentIntentId()).isEqualTo("pi_test_abc");
  }

  /**
   * AC #2 — the inventory port is invoked with the stable idempotency tuple
   * {@code orderUuid + ":stock.reserve"} per ADR-11 (so a retry hits the same inventory
   * sagaStepId and dedupes).
   */
  @Test
  void happyPath_passesIdempotencyKeyAsOrderUuidPlusStockReserve() {
    CheckoutStartedEvent event = newCheckoutStartedEvent();
    when(orderRepository.findByCheckoutUuid(event.getCheckoutUuid())).thenReturn(Optional.empty());
    when(inventoryAdapter.reserve(any(Order.class), anyList(), anyString()))
        .thenReturn(new JavaDirectInventoryReservationAdapter.Result(99L, 7L, Instant.now()));

    orchestrator.handle(event);

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    verify(inventoryAdapter).reserve(any(Order.class), anyList(), keyCaptor.capture());
    assertThat(keyCaptor.getValue()).isEqualTo("42:stock.reserve");
  }

  /** The orchestrator's entry guard rejects null checkoutUuid up-front. */
  @Test
  void handle_nullCheckoutUuid_throwsIllegalArgument() {
    CheckoutStartedEvent event = CheckoutStartedEvent.builder()
        .eventId(1L).aggregateType("Checkout").aggregateId(11L).occurredAt(Instant.now())
        .checkoutUuid(null).cartUuid(22L).userId("u-abc").tenantId("default")
        .shippingAddress(vn.vnpt.checkout.domain.ShippingAddress.builder()
            .recipientName("A").phone("0").addressLine1("a").city("HCM").province("HCM").country("VN")
            .build())
        .cartLines(List.of(CartLineSnapshot.builder().variantId(1001L).quantity(1).unitPriceMinor(1L).build()))
        .paymentIntentId("pi_x").build();

    assertThatThrownBy(() -> orchestrator.handle(event))
        .isInstanceOf(IllegalArgumentException.class);
    verify(orderRepository, never()).save(any(Order.class));
    verify(orderEventPublisher, never()).publishOrderCreated(any(Order.class));
  }

  /** AC #5 — resumeFromCreated re-runs the stock.reserve + payment.intent.created steps. */
  @Test
  void resumeFromCreated_appendsTwoTransitions_andAdvancesToPaymentPending() {
    Order order = Order.builder()
        .tenantId("default").cartUuid(22L).checkoutUuid(11L)
        .paymentIntentId("pi_test_abc").status(OrderStatus.CREATED).version(0L)
        .shippingAddress(vn.vnpt.checkout.domain.ShippingAddress.builder()
            .recipientName("A").phone("0").addressLine1("a").city("HCM").province("HCM").country("VN").build())
        .build();
    order.setUuid(42L);
    when(inventoryAdapter.reserve(any(Order.class), anyList(), anyString()))
        .thenReturn(new JavaDirectInventoryReservationAdapter.Result(99L, 7L, Instant.now()));

    orchestrator.resumeFromCreated(order);

    ArgumentCaptor<OrderStateTransition> txCaptor = ArgumentCaptor.forClass(OrderStateTransition.class);
    verify(transitionRepository, times(2)).save(txCaptor.capture());
    List<OrderStateTransition> txs = txCaptor.getAllValues();
    assertThat(txs.get(0).getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_STOCK_RESERVE);
    assertThat(txs.get(0).getFromState()).isEqualTo(OrderStatus.CREATED);
    assertThat(txs.get(0).getToState()).isEqualTo(OrderStatus.STOCK_RESERVED);
    assertThat(txs.get(1).getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_PAYMENT_INTENT_CREATED);
    assertThat(txs.get(1).getFromState()).isEqualTo(OrderStatus.STOCK_RESERVED);
    assertThat(txs.get(1).getToState()).isEqualTo(OrderStatus.PAYMENT_PENDING);

    verify(orderEventPublisher).publishOrderStockReserved(any(Order.class), eq(99L));
    verify(orderEventPublisher).publishOrderPaymentPending(any(Order.class));
  }

  /** AC #5 — resumeFromCreated is a no-op when the order has already advanced past CREATED. */
  @Test
  void resumeFromCreated_skipsWhenStatusAlreadyPastCreated() {
    Order order = Order.builder()
        .tenantId("default").cartUuid(22L).checkoutUuid(11L)
        .status(OrderStatus.PAYMENT_PENDING).version(2L).build();
    order.setUuid(42L);

    orchestrator.resumeFromCreated(order);

    verify(inventoryAdapter, never()).reserve(any(Order.class), anyList(), anyString());
    verify(transitionRepository, never()).save(any(OrderStateTransition.class));
    verify(orderEventPublisher, never()).publishOrderStockReserved(any(Order.class), anyLong());
  }

  /** AC #5 — resumeFromStockReserved advances to PAYMENT_PENDING. */
  @Test
  void resumeFromStockReserved_appendsPaymentPendingTransition() {
    Order order = Order.builder()
        .tenantId("default").cartUuid(22L).checkoutUuid(11L)
        .status(OrderStatus.STOCK_RESERVED).version(1L).build();
    order.setUuid(42L);

    orchestrator.resumeFromStockReserved(order);

    ArgumentCaptor<OrderStateTransition> txCaptor = ArgumentCaptor.forClass(OrderStateTransition.class);
    verify(transitionRepository).save(txCaptor.capture());
    OrderStateTransition tx = txCaptor.getValue();
    assertThat(tx.getFromState()).isEqualTo(OrderStatus.STOCK_RESERVED);
    assertThat(tx.getToState()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    assertThat(tx.getSagaStep()).isEqualTo(OrderSagaOrchestrator.STEP_PAYMENT_INTENT_CREATED);
    verify(orderEventPublisher).publishOrderPaymentPending(order);
  }

  /** AC #5 — resumeFromPaymentPending is intentionally a no-op (Epic 3 owns the webhook). */
  @Test
  void resumeFromPaymentPending_isNoOp() {
    Order order = Order.builder()
        .tenantId("default").cartUuid(22L).checkoutUuid(11L)
        .status(OrderStatus.PAYMENT_PENDING).version(2L).build();
    order.setUuid(42L);

    orchestrator.resumeFromPaymentPending(order);

    verify(transitionRepository, never()).save(any(OrderStateTransition.class));
    verify(orderEventPublisher, never()).publishOrderPaymentPending(any(Order.class));
    verify(orderEventPublisher, never()).publishOrderFailed(any(Order.class));
  }

  private CheckoutStartedEvent newCheckoutStartedEvent() {
    return CheckoutStartedEvent.builder()
        .eventId(1L)
        .aggregateType("Checkout")
        .aggregateId(11L)
        .occurredAt(Instant.now())
        .checkoutUuid(11L)
        .cartUuid(22L)
        .userId("u-abc")
        .tenantId("default")
        .shippingAddress(ShippingAddress.builder()
            .recipientName("Nguyen Van A")
            .phone("0901234567")
            .addressLine1("123 Le Loi")
            .city("HCM")
            .province("HCM")
            .country("VN")
            .build())
        .cartLines(List.of(CartLineSnapshot.builder()
            .variantId(1001L)
            .quantity(2)
            .unitPriceMinor(50_000L)
            .build()))
        .paymentIntentId("pi_test_abc")
        .build();
  }
}