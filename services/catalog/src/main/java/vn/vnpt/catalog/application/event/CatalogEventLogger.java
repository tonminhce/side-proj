package vn.vnpt.catalog.application.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vn.vnpt.catalog.application.port.ProcessedEventPort;
import vn.vnpt.catalog.domain.event.CatalogProductCreated;
import vn.vnpt.catalog.domain.event.CatalogProductDeleted;
import vn.vnpt.catalog.domain.event.CatalogProductPriceChanged;
import vn.vnpt.catalog.domain.event.CatalogProductUpdated;

/**
 * In-process listener for {@code catalog.product.*} events (Story 1.3 / AC #6, #17).
 *
 * <p>Why this exists: the first end-to-end proof that the Modulith outbox bridge is wired
 * correctly. The listener fires after {@code ModulithOutboxPublisher} writes the outbox row;
 * the listener's job is to insert a row into {@code processed_event} (the consumer-side
 * dedup table) using the outbox row's {@code event_id} as the UNIQUE key. AC #17's regression
 * guard is that exactly 1 row lands in {@code processed_event} after a {@code
 * createProduct} call.
 *
 * <p>This is NOT business logic. Story 1.5 (inventory ledger) wires the first real consumer;
 * Story 1.4 (admin read view) is the read-side. This listener exists only to prove the
 * bridge-to-listener path. Future consumers can copy-paste the {@code processedEvent}
 * insertion pattern.
 *
 * <p>Idempotency: a bridge redelivery hits the {@code processed_event.event_id} UNIQUE
 * constraint, and the {@code INSERT ... ON CONFLICT DO NOTHING} in {@code
 * ProcessedEventPort#append} treats the duplicate as a no-op. The test asserts
 * exactly 1 row, not 2.
 *
 * <p>Why we look up {@code event_id} from the outbox table: the Avro POJO does not carry the
 * Snowflake event_id — only the publisher knows it (it generates it). The listener runs in
 * the same transaction as the publisher's outbox INSERT, so the outbox row is visible.
 */
@Component
public class CatalogEventLogger {

  private static final Logger log = LoggerFactory.getLogger(CatalogEventLogger.class);
  private static final String CONSUMER = "catalog.CatalogEventLogger";

  private final ProcessedEventPort processed;
  private final DataSource dataSource;

  public CatalogEventLogger(ProcessedEventPort processed, DataSource dataSource) {
    this.processed = processed;
    this.dataSource = dataSource;
  }

  @ApplicationModuleListener
  void on(CatalogProductCreated event) {
    record(
        "catalog.product.created",
        event.getProductUuid(),
        latestOutboxEventId(event.getProductUuid(), "catalog.product.created"));
  }

  @ApplicationModuleListener
  void on(CatalogProductUpdated event) {
    record(
        "catalog.product.updated",
        event.getProductUuid(),
        latestOutboxEventId(event.getProductUuid(), "catalog.product.updated"));
  }

  @ApplicationModuleListener
  void on(CatalogProductPriceChanged event) {
    record(
        "catalog.product.price_changed",
        event.getVariantUuid(),
        latestOutboxEventId(event.getVariantUuid(), "catalog.product.price_changed"));
  }

  @ApplicationModuleListener
  void on(CatalogProductDeleted event) {
    record(
        "catalog.product.deleted",
        event.getProductUuid(),
        latestOutboxEventId(event.getProductUuid(), "catalog.product.deleted"));
  }

  private void record(String eventType, Long aggregateId, Long eventId) {
    if (eventId == null) {
      // Defensive: if the publisher didn't write the row (rolled back), don't crash the
      // listener — log and move on. The catalog-side roll-back should also roll back any
      // @ApplicationModuleListener writes (Modulith's TX joining); this is a guard for the
      // race where the listener fires before the INSERT is visible.
      log.warn("Skipping processed_event insert: outbox row not found for aggregate_id={}", aggregateId);
      return;
    }
    processed.append(
        CONSUMER, eventType, eventId, LocalDateTime.ofInstant(Instant.now(), TimeZone.getDefault().toZoneId()));
  }

  /**
   * Look up the most recent outbox row matching {@code (aggregate_id, event_type)} and return
   * its {@code event_id}. The publisher's INSERT and the listener's SELECT share the same
   * transaction (Modulith's {@code @ApplicationModuleListener} joins the surrounding
   * transaction by default).
   */
  private Long latestOutboxEventId(Long aggregateId, String eventType) {
    try (var conn = dataSource.getConnection();
        var ps =
            conn.prepareStatement(
                "SELECT event_id FROM outbox WHERE aggregate_id = ? AND event_type = ?"
                    + " ORDER BY id DESC LIMIT 1")) {
      ps.setLong(1, aggregateId);
      ps.setString(2, eventType);
      try (var rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong("event_id") : null;
      }
    } catch (Exception e) {
      log.warn("Failed to look up outbox event_id for aggregate_id={} type={}", aggregateId, eventType, e);
      return null;
    }
  }
}
