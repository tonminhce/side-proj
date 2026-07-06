package vn.vnpt.inventory.application;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.event.InventoryAdjusted;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * AdjustInventoryUseCase — Story 1.5 / FR-8, ADR-12.
 *
 * <p>Appends one row to the {@code inventory_ledger} and emits an outbox event in the SAME
 * transaction (ADR-04 atomicity: business state + outbox are atomic). The ledger is the SOLE
 * source of truth for inventory state; {@code on_hand} is a sum-derivation, not a column.
 *
 * <p>PONYTAIL: this use case does NOT enforce {@code on_hand >= 0}. Negative deltas are valid for
 * {@code "adjust"} reasons (lost-in-warehouse, damaged goods). The canonical oversell guard is
 * Story 1.6's reservation path ({@code SELECT … FOR UPDATE} against a derived table); not this
 * story. Adding an inventory check here would duplicate the reservation logic and bypass the
 * saga.
 *
 * <p>Idempotency key: each call generates a fresh {@code eventId} via
 * {@link SnowflakeIdGenerator#generateId()}. The {@code outbox.event_id} and
 * {@code inventory_ledger.event_id} both carry this value; downstream consumers dedup on it.
 *
 * <p>Story 1.3 sign-on-publish extension is a follow-up (Story 1.5 V002): outbound events are
 * currently unsigned. The producer-side signing lives in catalog; inventory consumes signed
 * events from catalog but does not yet sign its own outbound events.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AdjustInventoryUseCase {

  private final InventoryLedgerEntryRepository ledgerRepository;
  private final WarehouseRepository warehouseRepository;
  private final OutboxPublisher outbox;

  /**
   * Persist a ledger entry and emit an outbox event in the same transaction.
   *
   * @throws IllegalArgumentException if {@code delta == 0} or {@code reason == null}
   * @throws WarehouseNotFoundException if {@code warehouseId} does not exist
   */
  public InventoryLedgerEntry adjust(AdjustInventoryCommand cmd) {
    if (cmd.delta() == 0) {
      throw new IllegalArgumentException("delta must be non-zero");
    }
    if (cmd.reason() == null) {
      throw new IllegalArgumentException("reason is required");
    }
    warehouseRepository
        .findById(cmd.warehouseId())
        .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

    long eventId = SnowflakeIdGenerator.generateId();
    String reasonColumnValue = cmd.reason().toColumnValue();

    InventoryLedgerEntry entry =
        ledgerRepository.save(
            InventoryLedgerEntry.builder()
                .variantId(cmd.variantId())
                .warehouseId(cmd.warehouseId())
                .delta(cmd.delta())
                .reason(reasonColumnValue)
                .eventId(eventId)
                .build());

    outbox.append(
        "InventoryLedger",
        entry.getUuid(),
        "inventory." + reasonColumnValue,
        new InventoryAdjusted(
            entry.getUuid(),
            cmd.variantId(),
            cmd.warehouseId(),
            cmd.delta(),
            reasonColumnValue,
            eventId,
            Instant.now()),
        Map.of());

    return entry;
  }
}