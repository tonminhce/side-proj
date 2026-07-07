package vn.vnpt.checkout.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Pure-mockito unit tests for {@link OrderSagaRecoveryRunner} — Story 2.5 / FR-22 (AC #5).
 *
 * <p>The runner is constructed by hand; {@link ApplicationArguments} is mocked. Two paths
 * exercised: stuck {@code PAYMENT_PENDING} order gets resumed; terminal {@code PAID} order is
 * ignored.
 */
class OrderSagaRecoveryRunnerTest {

  private OrderRepository orderRepository;
  private OrderStateTransitionRepository transitionRepository;
  private OrderSagaOrchestrator orchestrator;
  private OrderSagaRecoveryRunner runner;

  @BeforeEach
  void setUp() {
    orderRepository = org.mockito.Mockito.mock(OrderRepository.class);
    transitionRepository = org.mockito.Mockito.mock(OrderStateTransitionRepository.class);
    orchestrator = org.mockito.Mockito.mock(OrderSagaOrchestrator.class);
    runner = new OrderSagaRecoveryRunner(orderRepository, transitionRepository, orchestrator,
        new SimpleMeterRegistry());
    // Set the @Value defaults — hand construction skips Spring's autowire of @Value.
    ReflectionTestUtils.setField(runner, "cutoffMinutes", 5L);
    ReflectionTestUtils.setField(runner, "maxPerBoot", 100);
  }

  private Order stuckOrder(Long uuid, OrderStatus status) {
    Order o = Order.builder()
        .tenantId("default")
        .cartUuid(uuid)
        .checkoutUuid(uuid + 1)
        .status(status)
        .version(1L)
        .build();
    o.setUuid(uuid);
    o.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
    return o;
  }

  @Test
  void resumesStuckPaymentPendingOrder() {
    Order stuck = stuckOrder(100L, OrderStatus.PAYMENT_PENDING);
    when(orderRepository.findStuckOrders(anyList(), any(LocalDateTime.class))).thenReturn(List.of(stuck));
    when(transitionRepository.findFirstByOrderUuidOrderByCreatedAtDesc(100L))
        .thenReturn(new OrderStateTransition(100L, OrderStatus.STOCK_RESERVED,
            OrderStatus.PAYMENT_PENDING, OrderSagaOrchestrator.STEP_PAYMENT_INTENT_CREATED, null));

    runner.run(org.mockito.Mockito.mock(ApplicationArguments.class));

    verify(orchestrator).resumeFromPaymentPending(stuck);
    verify(orchestrator, never()).resumeFromCreated(any());
    verify(orchestrator, never()).resumeFromStockReserved(any());
  }

  @Test
  void resumesStuckStockReservedOrder() {
    Order stuck = stuckOrder(101L, OrderStatus.STOCK_RESERVED);
    when(orderRepository.findStuckOrders(anyList(), any(LocalDateTime.class))).thenReturn(List.of(stuck));
    when(transitionRepository.findFirstByOrderUuidOrderByCreatedAtDesc(101L))
        .thenReturn(new OrderStateTransition(101L, OrderStatus.CREATED,
            OrderStatus.STOCK_RESERVED, OrderSagaOrchestrator.STEP_STOCK_RESERVE, null));

    runner.run(org.mockito.Mockito.mock(ApplicationArguments.class));

    verify(orchestrator).resumeFromStockReserved(stuck);
  }

  @Test
  void resumesStuckCreatedOrder() {
    Order stuck = stuckOrder(102L, OrderStatus.CREATED);
    when(orderRepository.findStuckOrders(anyList(), any(LocalDateTime.class))).thenReturn(List.of(stuck));
    when(transitionRepository.findFirstByOrderUuidOrderByCreatedAtDesc(102L))
        .thenReturn(new OrderStateTransition(102L, null, OrderStatus.CREATED,
            OrderSagaOrchestrator.STEP_CART_SUBMIT, null));

    runner.run(org.mockito.Mockito.mock(ApplicationArguments.class));

    verify(orchestrator).resumeFromCreated(stuck);
  }

  @Test
  void skipsTerminalOrders_paidNotInStuckQuery() {
    Order paid = stuckOrder(200L, OrderStatus.PAID);
    when(orderRepository.findStuckOrders(anyList(), any(LocalDateTime.class))).thenReturn(List.of(paid));
    when(transitionRepository.findFirstByOrderUuidOrderByCreatedAtDesc(200L))
        .thenReturn(new OrderStateTransition(200L, OrderStatus.PAYMENT_PENDING,
            OrderStatus.PAID, "payment_intent.succeeded", null));

    runner.run(org.mockito.Mockito.mock(ApplicationArguments.class));

    // Unknown saga step "payment_intent.succeeded" → log warn, no resume call.
    verify(orchestrator, never()).resumeFromCreated(any());
    verify(orchestrator, never()).resumeFromStockReserved(any());
    verify(orchestrator, never()).resumeFromPaymentPending(any());
  }

  @Test
  void emptyStuckList_isNoOp() {
    when(orderRepository.findStuckOrders(anyList(), any(LocalDateTime.class))).thenReturn(List.of());
    runner.run(org.mockito.Mockito.mock(ApplicationArguments.class));
    verify(orchestrator, never()).resumeFromCreated(any());
    verify(orchestrator, never()).resumeFromStockReserved(any());
    verify(orchestrator, never()).resumeFromPaymentPending(any());
  }
}