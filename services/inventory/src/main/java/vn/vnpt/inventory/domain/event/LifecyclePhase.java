package vn.vnpt.inventory.domain.event;

import java.util.Arrays;

/**
 * Lifecycle phase enum — Story 1.8 / FR-11 (unified {@code inventory.lifecycle} topic).
 *
 * <p>The unified phase discriminator for inventory lifecycle events. The wire value is
 * {@link #name()} ({@code SCREAMING_SNAKE_CASE}) — matching the existing
 * {@link vn.vnpt.inventory.domain.ReservationStatus#wireValue()} convention from Story 1.6.
 *
 * <p>Migration window: {@link #RESERVED} + {@link #RELEASED} dual-publish to the legacy
 * topics {@code inventory.reserved} / {@code inventory.released} for one Sprint (Sprint 9
 * migration cutoff). The new phases {@link #ALLOCATED} + {@link #SHIPPED} +
 * {@link #ADJUSTED} publish ONLY to the unified {@code inventory.lifecycle} topic.
 *
 * <p>{@code parseFromWireValue} accepts the wire string leniently (case-insensitive) so a
 * downstream consumer written in a different language can pass its native enum value.
 */
public enum LifecyclePhase {
  /** Cart checkout holds stock (Story 1.6). Dual-publish to legacy {@code inventory.reserved}. */
  RESERVED,
  /** Saga cancel OR sweeper TTL expiry (Story 1.6). Dual-publish to legacy {@code inventory.released}. */
  RELEASED,
  /** Payment auth success promotes reservation to order (Story 1.8). */
  ALLOCATED,
  /** Warehouse picks + carrier dispatch (Story 1.8). */
  SHIPPED,
  /** Manual inventory correction (Story 1.5/1.8). */
  ADJUSTED;

  /** Returns the wire value ({@link #name()}) — the SCREAMING_SNAKE_CASE JSON string. */
  public String wireValue() {
    return name();
  }

  /**
   * Lenient wire-value → enum lookup. Accepts {@code "reserved"}, {@code "RESERVED"},
   * {@code "Reserved"} — all map to {@link #RESERVED}. Returns {@code null} on unknown input
   * (caller decides the error mapping — typically a 400 with a "unknown phase" message).
   */
  public static LifecyclePhase parseFromWireValue(String wire) {
    if (wire == null || wire.isBlank()) {
      return null;
    }
    return Arrays.stream(values())
        .filter(p -> p.name().equalsIgnoreCase(wire.trim()))
        .findFirst()
        .orElse(null);
  }
}