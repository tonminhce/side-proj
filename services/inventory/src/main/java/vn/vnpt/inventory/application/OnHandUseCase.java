package vn.vnpt.inventory.application;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.inventory.application.query.AvailableStockView;
import vn.vnpt.inventory.application.query.OnHandView;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;

/**
 * OnHandUseCase — Story 1.5 / FR-8 (read-side sum-derivation); extended in Story 1.6 / FR-9
 * with {@code findAvailable}.
 *
 * <p>Returns the {@code OnHandView} (a read-side projection of
 * {@code SUM(delta) GROUP BY variant_id, warehouse_id}) for the requested variant. The
 * production read path is the JPA-derived {@code COALESCE(SUM(...), 0)} query in
 * {@code InventoryLedgerEntryRepository}; the {@code inventory_on_hand} Postgres VIEW (V002)
 * exists for ad-hoc DBA inspection only.
 *
 * <p>Returns {@code List} (not {@code Optional}): a variant may have multiple warehouses with
 * per-warehouse {@code on_hand} breakdown (Story 1.7 multi-warehouse). For v1 single-warehouse
 * default (ADR-06), the list has exactly 1 row per variant. Empty list for unseen variants.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OnHandUseCase {

  private final InventoryLedgerEntryRepository ledgerRepository;

  /** Sum-derivation across ALL warehouses for a variant. */
  public List<OnHandView> findOnHand(Long variantId) {
    return ledgerRepository.sumOnHandByVariantId(variantId);
  }

  /** Sum-derivation for a single (variant, warehouse) pair. */
  public List<OnHandView> findOnHandForWarehouse(Long variantId, Long warehouseId) {
    return ledgerRepository.sumOnHandByVariantIdAndWarehouseId(variantId, warehouseId);
  }

  /**
   * Available stock for a (variant, warehouse) pair — Story 1.6 / FR-9.
   *
   * <p>{@code available = onHand - SUM(active reservations)}. Returns {@link Optional} —
   * empty when the variant has never been seen (no ledger rows AND no reservations).
   *
   * <p>This is the pre-flight check for {@code ReserveInventoryUseCase.reserve(...)}; the
   * canonical authoritative check is the {@code SELECT … FOR UPDATE} inside that use case's
   * transaction. The pre-flight is fail-fast — a stale reading here does not guarantee success.
   */
  public Optional<AvailableStockView> findAvailable(Long variantId, Long warehouseId) {
    return ledgerRepository.findAvailable(variantId, warehouseId);
  }
}