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
}