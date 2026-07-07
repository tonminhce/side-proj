package vn.vnpt.cart.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Cart status — Story 2.1 / FR-14.
 *
 * <p>{@code ANONYMOUS} = cookie-bound; {@code ACTIVE} = user-bound; {@code MERGED} = terminal
 * (source of a merge); {@code ABANDONED} = terminal (30-day sweep — Story 2.2); {@code CHECKED_OUT}
 * = terminal (Story 2.3). Wire format: SCREAMING_SNAKE_CASE JSON value ({@code @Enumerated(STRING)}).
 */
public enum CartStatus {
  ANONYMOUS,
  ACTIVE,
  MERGED,
  ABANDONED,
  CHECKED_OUT;

  /** Terminal statuses that the sweeper must NOT re-process. */
  public static final Set<CartStatus> TERMINAL = EnumSet.of(MERGED, ABANDONED, CHECKED_OUT);

  /** True if this status is terminal — the sweeper is a no-op on terminal carts. */
  public boolean isTerminal() {
    return TERMINAL.contains(this);
  }
}
