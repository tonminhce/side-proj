package vn.vnpt.inventory.application.query;

import java.time.LocalDateTime;

/**
 * Read-side projection of {@code SUM(delta)} per {@code (variant_id, warehouse_id)}.
 *
 * <p>Two paths produce this record:
 *
 * <ol>
 *   <li>Production path: JPA-derived {@code COALESCE(SUM(...), 0)} query in
 *       {@code InventoryLedgerEntryRepository.sumOnHandByVariantId} (or variant+warehouse).
 *   <li>Debugging surface: Postgres VIEW {@code inventory_on_hand} (V002) for ad-hoc DBA
 *       inspection. The use case uses path 1; the view exists for ops.
 * </ol>
 *
 * <p>{@code onHand} is {@code Long} (not {@code long}) so the COALESCE-based query can return a
 * {@code null} if the GROUP BY is empty. The use case returns an empty list for unseen variants;
 * downstream callers handle absence.
 *
 * <p>{@code lastMovementAt} is {@code LocalDateTime} to match {@code BaseEntity.createdAt}
 * (JPA-derived {@code MAX(l.createdAt)} returns the same type as the entity field).
 *
 * @param variantId the variant's Snowflake id
 * @param warehouseId the warehouse's Snowflake id
 * @param onHand sum of deltas (signed; may be 0 or negative for "adjust" reasons)
 * @param entryCount number of ledger rows contributing to this sum
 * @param lastMovementAt the {@code MAX(created_at)} of the contributing rows
 */
public record OnHandView(
    Long variantId,
    Long warehouseId,
    Long onHand,
    Long entryCount,
    LocalDateTime lastMovementAt) {}