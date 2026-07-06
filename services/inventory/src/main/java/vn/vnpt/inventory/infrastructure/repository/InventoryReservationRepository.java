package vn.vnpt.inventory.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;

/**
 * Spring Data JPA repository for {@link InventoryReservation}.
 *
 * <p>Append-only invariant (Story 1.6 / AC #5): this repository declares NO {@code void
 * delete*(...)} methods. The reservation lifecycle is {@link ReservationStatus} transitions,
 * not row deletion (audit trail preservation; same philosophy as the ledger). Enforced by
 * {@code InventoryPackageBoundaryTest.inventory_reservation_isTerminalOnly}.
 */
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

  /**
   * ADR-11 idempotency lookup. Returns the existing reservation on saga retries — same
   * {@code saga_step_id} returns the same row, no double-decrement.
   */
  Optional<InventoryReservation> findBySagaStepId(String sagaStepId);

  /**
   * Sweeper query — returns ACTIVE reservations past their TTL. Ordered by {@code expires_at}
   * for sequential processing. Bounded by the JPQL limit on the
   * {@code ReservationSweeperJob#sweepExpired} call site.
   */
  List<InventoryReservation> findByStatusAndExpiresAtBefore(
      ReservationStatus status, Instant cutoff);

  /**
   * Variant-scoped query for saga re-derivation. Not consumed in Story 1.6 — ships for
   * forward-compatibility with Story 2.5's checkout saga step.
   */
  List<InventoryReservation> findByVariantIdAndStatus(Long variantId, ReservationStatus status);
}