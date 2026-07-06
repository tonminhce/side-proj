package vn.vnpt.inventory.domain.event;

import java.time.Instant;

/**
 * Hand-written inventory adjustment event — Story 1.5 placeholder.
 *
 * <p>Story 1.8 (Avro lifecycle events, FR-11) replaces this hand-written record with the
 * Avro-generated type at the same FQN. The {@code AdjustInventoryUseCase.adjust(...)} signature
 * does NOT change when the replacement lands.
 *
 * @param ledgerEntryUuid Snowflake id of the {@code inventory_ledger} row
 * @param variantId Snowflake id of the variant (cross-service reference to catalog)
 * @param warehouseId Snowflake id of the warehouse
 * @param delta signed delta (positive inbound, negative outbound)
 * @param reason lowercase reason string (the {@link vn.vnpt.inventory.domain.InventoryReason#toColumnValue()})
 * @param eventId Snowflake id of the outbox row (the idempotency key)
 * @param occurredAt event timestamp
 */
public record InventoryAdjusted(
    Long ledgerEntryUuid,
    Long variantId,
    Long warehouseId,
    long delta,
    String reason,
    Long eventId,
    Instant occurredAt) {}