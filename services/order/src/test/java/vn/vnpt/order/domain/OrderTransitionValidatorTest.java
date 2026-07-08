package vn.vnpt.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the order state machine — Story 4.1 + 4.2.
 */
class OrderTransitionValidatorTest {

  @Test
  void PLACED_to_PAID_allowed() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PLACED, OrderState.PAID)).isTrue();
  }

  @Test
  void PAID_to_ALLOCATED_allowed() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PAID, OrderState.ALLOCATED)).isTrue();
  }

  @Test
  void ALLOCATED_to_PACKING_allowed() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.ALLOCATED, OrderState.PACKING)).isTrue();
  }

  @Test
  void PACKING_to_PACKED_allowed() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PACKING, OrderState.PACKED)).isTrue();
  }

  @Test
  void PLACED_to_ALLOCATED_rejected_mustGoThroughPaid() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PLACED, OrderState.ALLOCATED)).isFalse();
  }

  @Test
  void PAID_to_PACKING_rejected_mustGoThroughAllocated() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PAID, OrderState.PACKING)).isFalse();
  }

  @Test
  void PAID_to_REFUNDED_allowed_paymentRefundedSaga() {
    // Story 3.5 follow-up #4: PAID → REFUNDED is the saga transition on payment.refunded.
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PAID, OrderState.REFUNDED)).isTrue();
  }

  @Test
  void REFUNDED_isTerminal_noFurtherTransitions() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.REFUNDED, OrderState.PAID)).isFalse();
    assertThat(v.isAllowed(OrderState.REFUNDED, OrderState.CANCELLED)).isFalse();
    assertThat(v.isAllowed(OrderState.REFUNDED, OrderState.SHIPPED)).isFalse();
  }

  @Test
  void PLACED_to_REFUNDED_rejected_mustGoThroughPaid() {
    // Refund from PLACED is rejected — must transition through PAID first.
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PLACED, OrderState.REFUNDED)).isFalse();
  }
}