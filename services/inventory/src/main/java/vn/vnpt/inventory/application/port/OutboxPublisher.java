package vn.vnpt.inventory.application.port;

import java.util.Map;

/**
 * Outbox publisher port — application layer contract for writing business events into the
 * service-local {@code outbox} table atomically with business state (ADR-14, ADR-04, ADR-20).
 *
 * <p>Intentionally identical to {@code vn.vnpt.catalog.application.port.OutboxPublisher} (the
 * 5-arg signature carrying the {@code signatures} map was added in Story 1.3). Cross-service port
 * sharing would require a common port module in {@code util/}; that's YAGNI for Story 1.5. Each
 * service owns its own port.
 *
 * <p>The implementation in {@code vn.vnpt.inventory.infrastructure.outbox.ModulithOutboxPublisher}
 * writes the row via {@code JdbcTemplate} and fires {@code ApplicationEventPublisher.publishEvent}
 * so in-process {@code @ApplicationModuleListener}s consume the event in the same transaction.
 */
public interface OutboxPublisher {
  /**
   * Append an event to the outbox.
   *
   * @param aggregateType aggregate root name (e.g. {@code "InventoryLedger"}) — driver of the
   *     {@code outbox.aggregate_type} column
   * @param aggregateId Snowflake id of the aggregate root (the {@code InventoryLedgerEntry.uuid})
   * @param eventType dotted event type string ({@code "inventory.receive"})
   * @param event domain event instance (any type — Jackson serializes via runtime class)
   * @param signatures per-service signature map. Pass {@link Map#of()} when the publisher is
   *     expected to compute the signature itself; Story 1.5's inventory publisher does NOT sign
   *     (signing lands with V002). Story 1.8 wires the lifecycle events with full signing.
   */
  void append(
      String aggregateType,
      Long aggregateId,
      String eventType,
      Object event,
      Map<String, String> signatures);
}