package vn.vnpt.inventory.application;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;

/**
 * PickWarehouseForReservationUseCase — Story 1.7 / FR-10 (ADR-06 multi-warehouse stretch).
 *
 * <p>Selects a warehouse id for a reservation given a variant, shipping region, and requested
 * quantity. The picker:
 *
 * <ol>
 *   <li>Tries in-region active warehouses — first one with {@code onHand >= requested} wins.
 *   <li>Falls back to ALL active warehouses (any region) when no in-region warehouse has
 *       enough stock. Cross-region picks emit a WARN log so ops can detect chronic
 *       cross-region fulfillment (a signal that warehouse capacity needs rebalancing).
 *   <li>Returns {@link Optional#empty()} when no warehouse anywhere has enough stock. The
 *       caller maps this to {@link vn.vnpt.inventory.domain.exception.InsufficientStockException}.
 * </ol>
 *
 * <p>The picker is read-only ({@code @Transactional(readOnly = true)}). The canonical
 * authoritative stock check is the {@code SELECT … FOR UPDATE} inside
 * {@link ReserveInventoryUseCase}; this picker is informational dispatch. Picker result is
 * idempotent on its inputs (stateless).
 *
 * <p>Ponytail: load-balancing across N in-region warehouses is YAGNI for v1 — first match wins.
 * Distance-based scoring is a Future Story 8.x admin optimization.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PickWarehouseForReservationUseCase {

  private final WarehouseRepository warehouseRepository;
  private final InventoryLedgerEntryRepository ledgerRepository;

  /**
   * @param variantId the variant being reserved
   * @param region the customer's shipping region (NORTH | SOUTH | CENTRAL)
   * @param requestedQuantity the units to reserve; must be positive
   * @return the chosen warehouse id, or {@code Optional.empty()} when no warehouse has
   *     enough stock for the requested quantity.
   */
  public Optional<Long> pickWarehouseId(Long variantId, Region region, long requestedQuantity) {
    // Step 1: region-scoped candidates.
    List<Warehouse> inRegion =
        warehouseRepository.findByRegionAndIsActiveTrueAndIsDeletedFalse(region);

    Optional<Long> inRegionPick =
        inRegion.stream()
            .map(Warehouse::getUuid)
            .filter(whId -> hasEnoughStock(variantId, whId, requestedQuantity))
            .findFirst();

    if (inRegionPick.isPresent()) {
      return inRegionPick;
    }

    // Step 2: cross-region fallback — try every active warehouse regardless of region.
    List<Warehouse> allActive = warehouseRepository.findByIsActiveTrueAndIsDeletedFalse();

    Optional<Long> crossRegionPick =
        allActive.stream()
            .filter(wh -> wh.getRegion() != region) // skip already-checked in-region candidates
            .map(Warehouse::getUuid)
            .filter(whId -> hasEnoughStock(variantId, whId, requestedQuantity))
            .findFirst();

    crossRegionPick.ifPresent(
        whId ->
            log.warn(
                "pickwarehouse.crossregion fallback region={} → warehouse={}",
                region,
                whId));

    return crossRegionPick;
  }

  /**
   * {@code onHand = SUM(delta)} per {@code (variant, warehouse)}. Story 1.6's Issue 9 fix
   * established that reservations already decrement {@code on_hand} via the {@code delta=-qty}
   * ledger row, so {@code onHand} reflects available stock directly.
   */
  private boolean hasEnoughStock(Long variantId, Long warehouseId, long requestedQuantity) {
    long onHand =
        ledgerRepository.sumOnHandByVariantIdAndWarehouseId(variantId, warehouseId).stream()
            .findFirst()
            .map(v -> v.onHand() == null ? 0L : v.onHand())
            .orElse(0L);
    return onHand >= requestedQuantity;
  }
}