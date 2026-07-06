package vn.vnpt.inventory.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import vn.vnpt.inventory.application.query.AvailableStockView;
import vn.vnpt.inventory.application.query.OnHandView;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;

/**
 * Spring Data JPA repository for {@link InventoryLedgerEntry}.
 *
 * <p>Append-only invariant (AC #7): this repository declares NO {@code void delete*(...)}
 * methods. The absence is enforced by {@code InventoryPackageBoundaryTest.inventory_writesOnlyToInventoryLedger}
 * (ArchUnit-style reflection check). A future hardening story may add a Postgres trigger for
 * defense-in-depth; for v1 the convention + test are sufficient.
 *
 * <p>The {@code sumOnHand*} queries wrap {@code SUM(...)} in {@code COALESCE(... , 0)} so an
 * empty group returns 0 (not {@code null}). The {@code List} return type accommodates the
 * multi-warehouse case (Story 1.7); for v1 single-warehouse default (ADR-06) the list has 1 row.
 */
public interface InventoryLedgerEntryRepository
    extends JpaRepository<InventoryLedgerEntry, Long> {

  /** All ledger entries for a variant, across all warehouses. */
  List<InventoryLedgerEntry> findByVariantId(Long variantId);

  /** All ledger entries for a variant at one warehouse. */
  List<InventoryLedgerEntry> findByVariantIdAndWarehouseId(Long variantId, Long warehouseId);

  /**
   * Idempotency lookup. Returns {@code Optional} (the row may not exist if the inbound event was
   * rejected before insert). The {@code uq_inventory_ledger_event_id} UNIQUE constraint catches
   * duplicate inserts at the DB layer; the listener catches {@code DataIntegrityViolationException}
   * (see {@code CatalogEventListener}).
   */
  Optional<InventoryLedgerEntry> findByEventId(Long eventId);

  /**
   * Sum-derivation across all warehouses for a variant. Returns empty list for unseen variants.
   * {@code COALESCE(SUM(...), 0)} ensures the sum is 0 (not null) when the group is non-empty
   * but contains only zero-delta rows; the empty-list-for-empty-group case is handled by JPA.
   */
  @Query(
      "SELECT new vn.vnpt.inventory.application.query.OnHandView("
          + "  l.variantId, l.warehouseId, COALESCE(SUM(l.delta), 0), COUNT(l), MAX(l.createdAt)) "
          + "FROM InventoryLedgerEntry l "
          + "WHERE l.variantId = :variantId "
          + "GROUP BY l.variantId, l.warehouseId")
  List<OnHandView> sumOnHandByVariantId(Long variantId);

  /**
   * Sum-derivation for a single (variant, warehouse) pair. Returns a list with 0 or 1 row.
   */
  @Query(
      "SELECT new vn.vnpt.inventory.application.query.OnHandView("
          + "  l.variantId, l.warehouseId, COALESCE(SUM(l.delta), 0), COUNT(l), MAX(l.createdAt)) "
          + "FROM InventoryLedgerEntry l "
          + "WHERE l.variantId = :variantId AND l.warehouseId = :warehouseId "
          + "GROUP BY l.variantId, l.warehouseId")
  List<OnHandView> sumOnHandByVariantIdAndWarehouseId(Long variantId, Long warehouseId);

  /**
   * Available stock for {@code (variantId, warehouseId)} — the FR-9 read path.
   *
   * <p>{@code available = SUM(delta)} per pair. Because {@code ReserveInventoryUseCase} appends
   * a {@code delta=-qty} ledger row for every active reservation, the ledger already reflects
   * the reservation decrement — subtracting active reservations a second time would double-count.
   *
   * <p>The {@code activeReservations} projection is reported separately for ops visibility; the
   * canonical {@code available} field is the simple ledger sum.
   *
   * <p>Returns {@code Optional} (empty when no ledger rows exist — variant unseen).
   *
   * <p>ponytail: JPQL enums need the FQN — {@code vn.vnpt.inventory.domain.ReservationStatus.ACTIVE}
   * — because Hibernate doesn't infer enum constants from import statements.
   */
  @Query(
      "SELECT new vn.vnpt.inventory.application.query.AvailableStockView("
          + "  l.variantId, l.warehouseId,"
          + "  COALESCE(SUM(l.delta), 0),"
          + "  COALESCE((SELECT SUM(r.quantity) FROM InventoryReservation r"
          + "    WHERE r.variantId = :variantId AND r.warehouseId = :warehouseId"
          + "    AND r.status = vn.vnpt.inventory.domain.ReservationStatus.ACTIVE), 0),"
          + "  COALESCE(SUM(l.delta), 0)) "
          + "FROM InventoryLedgerEntry l "
          + "WHERE l.variantId = :variantId AND l.warehouseId = :warehouseId "
          + "GROUP BY l.variantId, l.warehouseId")
  Optional<AvailableStockView> findAvailable(Long variantId, Long warehouseId);

  /**
   * Acquire a Postgres {@code SELECT … FOR UPDATE} row lock on all {@code inventory_ledger}
   * rows for the {@code (variantId, warehouseId)} pair. The FR-9 canonical oversell guard
   * (DI-01 root-cause fix): concurrent reserves serialize on this lock; the second commit sees
   * the first's {@code -delta} and decremented reservation count, returning 409.
   *
   * <p>Called from {@code ReserveInventoryUseCase.reserve(...)} inside the {@code @Transactional}
   * boundary. Returns the locked ledger rows (or empty list if none yet exist for the pair).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT l FROM InventoryLedgerEntry l"
          + " WHERE l.variantId = :variantId AND l.warehouseId = :warehouseId")
  List<InventoryLedgerEntry> lockLedgerByVariantAndWarehouse(Long variantId, Long warehouseId);
}