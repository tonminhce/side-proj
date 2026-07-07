package vn.vnpt.checkout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import vn.vnpt.checkout.domain.exception.OrderIllegalStateTransitionException;

/**
 * Pure-domain FSM tests for {@link Order#transitionTo} — Story 2.5 / FR-22.
 *
 * <p>No Testcontainers, no Spring context — the aggregate's transitions are pure Java.
 */
class OrderTest {

  /** All edges in the allowed matrix. */
  static Stream<Arguments> legalEdges() {
    return Stream.of(
        Arguments.of(OrderStatus.CREATED, OrderStatus.STOCK_RESERVED, "stock.reserve"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.FAILED, "stock.reserve"),
        Arguments.of(OrderStatus.STOCK_RESERVED, OrderStatus.PAYMENT_PENDING, "payment.intent.created"),
        Arguments.of(OrderStatus.STOCK_RESERVED, OrderStatus.FAILED, "stock.reserve"),
        Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID, "payment_intent.succeeded"),
        Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.FAILED, "payment_intent.payment_failed"),
        Arguments.of(OrderStatus.PAYMENT_PENDING, OrderStatus.EXPIRED, "saga.timeout"));
  }

  @ParameterizedTest
  @MethodSource("legalEdges")
  void legalTransition_recordsPendingTransition(OrderStatus from, OrderStatus to, String sagaStep) {
    Order order = newOrder(from);
    order.transitionTo(to, sagaStep, null);

    assertThat(order.getStatus()).isEqualTo(to);
    assertThat(order.getPendingTransitions()).hasSize(1);
    assertThat(order.getPendingTransitions().get(0).getFromState()).isEqualTo(from);
    assertThat(order.getPendingTransitions().get(0).getToState()).isEqualTo(to);
    assertThat(order.getPendingTransitions().get(0).getSagaStep()).isEqualTo(sagaStep);
  }

  /** Self-loops are rejected. */
  @Test
  void selfLoop_throws() {
    Order order = newOrder(OrderStatus.CREATED);
    assertThatThrownBy(() -> order.transitionTo(OrderStatus.CREATED, "noop", null))
        .isInstanceOf(OrderIllegalStateTransitionException.class);
  }

  /** Skipping a state (e.g. CREATED → PAYMENT_PENDING) is rejected. */
  @Test
  void skipState_throws() {
    Order order = newOrder(OrderStatus.CREATED);
    assertThatThrownBy(() -> order.transitionTo(OrderStatus.PAYMENT_PENDING, "skip", null))
        .isInstanceOf(OrderIllegalStateTransitionException.class);
  }

  /** Terminal → any state is rejected (terminal has no outbound edges). */
  @Test
  void fromTerminal_throws() {
    for (OrderStatus terminal : OrderStatus.TERMINAL) {
      Order order = newOrder(terminal);
      assertThatThrownBy(() -> order.transitionTo(OrderStatus.CREATED, "back", null))
          .isInstanceOf(OrderIllegalStateTransitionException.class)
          .as("terminal=%s should reject transition", terminal);
    }
  }

  /** Status mutation carries failureReason into the transition log row. */
  @Test
  void failureTransition_carriesReason() {
    Order order = newOrder(OrderStatus.CREATED);
    order.transitionTo(OrderStatus.FAILED, "stock.reserve", "INSUFFICIENT_STOCK");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getFailureReason()).isEqualTo("INSUFFICIENT_STOCK");
    assertThat(order.getFailureSagaStep()).isEqualTo("stock.reserve");
    assertThat(order.getPendingTransitions().get(0).getFailureReason())
        .isEqualTo("INSUFFICIENT_STOCK");
  }

  /** isInFlight / isTerminal helpers cover the saga's IN_FLIGHT/TERMINAL split. */
  @Test
  void inFlightAndTerminal_helpers() {
    assertThat(OrderStatus.CREATED.isInFlight()).isTrue();
    assertThat(OrderStatus.STOCK_RESERVED.isInFlight()).isTrue();
    assertThat(OrderStatus.PAYMENT_PENDING.isInFlight()).isTrue();
    assertThat(OrderStatus.PAID.isTerminal()).isTrue();
    assertThat(OrderStatus.FAILED.isTerminal()).isTrue();
    assertThat(OrderStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(OrderStatus.EXPIRED.isTerminal()).isTrue();
    assertThat(OrderStatus.COMPENSATED.isTerminal()).isTrue();
  }

  private static Order newOrder(OrderStatus status) {
    return Order.builder()
        .tenantId("default")
        .cartUuid(1L)
        .checkoutUuid(2L)
        .status(status)
        .version(0L)
        .build();
  }
}