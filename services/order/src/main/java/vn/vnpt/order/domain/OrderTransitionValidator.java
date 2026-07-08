package vn.vnpt.order.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Order state transition validator — Story 4.1 + 4.2 + 4.4 + 3.5 follow-up #4.
 * The forward map covers the saga state machine; Story 4.4 adds AMENDED and CANCELLED as
 * valid transitions from any non-terminal state (the 30-min edit window is enforced at the
 * use-case layer, not here). Story 3.5 follow-up #4 adds REFUNDED as a terminal transition
 * from PAID (the payment.refunded saga listener path).
 */
@Component
public class OrderTransitionValidator {

  private static final Set<OrderState> TERMINAL = EnumSet.of(
      OrderState.SHIPPED, OrderState.DELIVERED, OrderState.CANCELLED, OrderState.REFUNDED);

  private static final Map<OrderState, Set<OrderState>> ALLOWED = new EnumMap<>(OrderState.class);

  static {
    // Forward saga map + AMENDED / CANCELLED allowed from any non-terminal state
    // (Story 4.4: amend + cancel are valid from PLACED, PAID, ALLOCATED, PACKING, PACKED).
    // REFUNDED is terminal from PAID (Story 3.5 follow-up #4: payment.refunded saga).
    ALLOWED.put(OrderState.PLACED, EnumSet.of(OrderState.PAID, OrderState.AMENDED, OrderState.CANCELLED));
    ALLOWED.put(OrderState.PAID, EnumSet.of(OrderState.ALLOCATED, OrderState.AMENDED, OrderState.CANCELLED, OrderState.REFUNDED));
    ALLOWED.put(OrderState.ALLOCATED, EnumSet.of(OrderState.PACKING, OrderState.AMENDED, OrderState.CANCELLED));
    ALLOWED.put(OrderState.PACKING, EnumSet.of(OrderState.PACKED, OrderState.AMENDED, OrderState.CANCELLED));
    ALLOWED.put(OrderState.PACKED, EnumSet.of(OrderState.SHIPPED, OrderState.AMENDED, OrderState.CANCELLED));
    ALLOWED.put(OrderState.AMENDED, EnumSet.of(OrderState.CANCELLED));  // amend-then-cancel within window
    ALLOWED.put(OrderState.SHIPPED, Set.of(OrderState.DELIVERED));
    ALLOWED.put(OrderState.DELIVERED, Set.of());
    ALLOWED.put(OrderState.REFUNDED, Set.of());  // REFUNDED is terminal
  }

  public boolean isAllowed(OrderState from, OrderState to) {
    if (from == null) {
      // Genesis transition (from = null) only allows the initial state.
      return to == OrderState.PLACED;
    }
    if (TERMINAL.contains(from)) {
      return false;
    }
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }
}