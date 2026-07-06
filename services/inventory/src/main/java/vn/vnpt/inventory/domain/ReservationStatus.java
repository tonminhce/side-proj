package vn.vnpt.inventory.domain;

/**
 * Reservation lifecycle — Story 1.6 / FR-9, ADR-12.
 *
 * <p>The sweeper filters {@code status = ACTIVE} only. Once a reservation leaves ACTIVE, it
 * stays terminal (audit trail preservation; same append-only philosophy as the ledger).
 *
 * <p>Stored as {@code VARCHAR(32)} in {@code inventory_reservation.status} via
 * {@code @Enumerated(EnumType.STRING)} on the entity — adding a new status is a code change,
 * not a migration. The column value is the {@link #name()} of the enum constant
 * ({@code "ACTIVE"}, {@code "RELEASED"}, {@code "COMMITTED"}).
 */
public enum ReservationStatus {

  /** Reservation is active; holds stock. Sweeper expires rows in this status past their TTL. */
  ACTIVE,

  /** Reservation was released (saga cancel OR sweeper TTL expiry). Terminal — no further state changes. */
  RELEASED,

  /** Reservation was committed to an order allocation. Terminal — future Story 4.1 saga step. */
  COMMITTED;

  /**
   * @return {@code true} for terminal states (RELEASED, COMMITTED); {@code false} for ACTIVE.
   *     The sweeper + release path use this to short-circuit duplicate work.
   */
  public boolean isTerminal() {
    return this != ACTIVE;
  }
}