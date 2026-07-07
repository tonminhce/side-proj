package vn.vnpt.checkout.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Checkout status — Story 2.3 / FR-19, FR-21.
 *
 * <p>{@code CREATED} = transient state reserved for Story 2.5's saga orchestrator (the saga may briefly
 * INSERT a row in {@code CREATED} before transitioning to {@code PAYMENT_PENDING}); Story 2.3's
 * {@code StartCheckoutUseCase} inserts directly in {@code PAYMENT_PENDING} per the AC's mandated
 * initial state. {@code PAID} / {@code FAILED} / {@code CANCELLED} / {@code EXPIRED} are terminal;
 * the saga (Story 2.5) drives these transitions based on outcome.
 *
 * <p>Wire format: SCREAMING_SNAKE_CASE JSON value ({@code @Enumerated(STRING)}).
 */
public enum CheckoutStatus {
  CREATED,
  PAYMENT_PENDING,
  PAID,
  FAILED,
  CANCELLED,
  EXPIRED;

  /** Terminal statuses — the checkout FSM ends here. */
  public static final Set<CheckoutStatus> TERMINAL = EnumSet.of(PAID, FAILED, CANCELLED, EXPIRED);

  /** True if this status is terminal. */
  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }
}