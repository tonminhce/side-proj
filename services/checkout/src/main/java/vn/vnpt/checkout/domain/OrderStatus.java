package vn.vnpt.checkout.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order status FSM — Story 2.5 / FR-22, FR-23 (ADR-12).
 *
 * <p>The full enum is declared here so {@link Order#transitionTo} doesn't need an extension in Epic 3
 * / Epic 4 when terminal transitions land. Story 2.5 only wires the first 4 transitions
 * ({@code (none) → CREATED → STOCK_RESERVED → PAYMENT_PENDING} plus the {@code CREATED → FAILED}
 * branch); PAID / CANCELLED / EXPIRED / COMPENSATED are reserved for Epic 3 (Stripe webhook) and
 * Epic 4 (post-payment fulfillment).
 *
 * <p>Wire format: SCREAMING_SNAKE_CASE JSON value ({@code @Enumerated(STRING)}).
 */
public enum OrderStatus {

  CREATED,
  STOCK_RESERVED,
  PAYMENT_PENDING,
  PAID,
  FAILED,
  CANCELLED,
  EXPIRED,
  COMPENSATED;

  /** In-flight (non-terminal, non-PAID) statuses the recovery runner picks up. */
  public static final Set<OrderStatus> IN_FLIGHT =
      EnumSet.of(CREATED, STOCK_RESERVED, PAYMENT_PENDING);

  /** Terminal statuses — no outbound transition. */
  public static final Set<OrderStatus> TERMINAL =
      EnumSet.of(PAID, FAILED, CANCELLED, EXPIRED, COMPENSATED);

  /**
   * Allowed transition edges for {@link Order#transitionTo}. Keys are the source state; values are
   * the allowed destinations. Anything not in this map throws
   * {@code OrderIllegalStateTransitionException}.
   */
  static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
      CREATED, EnumSet.of(STOCK_RESERVED, FAILED),
      STOCK_RESERVED, EnumSet.of(PAYMENT_PENDING, FAILED),
      PAYMENT_PENDING, EnumSet.of(PAID, FAILED, EXPIRED));

  /** True if this status is terminal. */
  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }

  /** True if this status is in-flight (the recovery runner picks it up). */
  public boolean isInFlight() {
    return IN_FLIGHT.contains(this);
  }

  /** True if {@code this → next} is an allowed transition edge. */
  public boolean canTransitionTo(OrderStatus next) {
    return ALLOWED.getOrDefault(this, Set.of()).contains(next);
  }
}