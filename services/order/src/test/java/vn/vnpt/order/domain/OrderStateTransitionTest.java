package vn.vnpt.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the order state machine — Story 4.1.
 */
class OrderStateTransitionTest {

  @Test
  void state_PLACED_isValidInitialState() {
    assertThat(OrderState.PLACED).isNotNull();
    assertThat(OrderState.PLACED.name()).isEqualTo("PLACED");
  }

  @Test
  void state_PLACED_to_PAID_isAllowedTransition() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PLACED, OrderState.PAID)).isTrue();
  }

  @Test
  void state_PLACED_to_SHIPPED_isRejectedForStory41() {
    // PLACED → SHIPPED skips PAID/ALLOCATED/PACKING/PACKED. Validator rejects it.
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(OrderState.PLACED, OrderState.SHIPPED)).isFalse();
  }

  @Test
  void nullFrom_allowsGenesis_PLACEDOnly() {
    OrderTransitionValidator v = new OrderTransitionValidator();
    assertThat(v.isAllowed(null, OrderState.PLACED)).isTrue();
    assertThat(v.isAllowed(null, OrderState.PAID)).isFalse();
  }
}