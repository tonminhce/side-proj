package vn.vnpt.inventory.application.event;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.catalog.domain.event.CatalogProductCreated;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;

/**
 * CatalogEventListener — Story 1.5 / AC #14 — first in-process event consumer in the platform.
 *
 * <p>Consumes {@link CatalogProductCreated} (catalog's first Avro event from Story 1.3) and
 * inserts an idempotent beacon row into {@code inventory_ledger} for the new variant:
 *
 * <ul>
 *   <li>Seeds a default {@code Warehouse} if none exists (v1 single-warehouse default, ADR-06).
 *   <li>Inserts a {@code delta = 0}, {@code reason = "received"} ledger row keyed by a
 *       {@link SnowflakeIdGenerator#generateId() generated} {@code eventId} as the cross-aggregate
 *       idempotency key. The {@code uq_inventory_ledger_event_id} UNIQUE constraint catches
 *       redeliveries; the listener treats {@link DataIntegrityViolationException} as a no-op
 *       (NFR-IDEM-1).
 * </ul>
 *
 * <p>Why {@code delta = 0}: the listener does not know initial stock; that's the admin's job
 * (Story 8.1). The ledger row is the CANONICAL IDEMPOTENCY BEACON — same event redelivering hits
 * the unique constraint and is caught, treated as a no-op.
 *
 * <p><b>HMAC VERIFY (ADR-20 consumer half):</b> the listener verifies the producer's signature
 * before inserting. The default behavior:
 *
 * <ul>
 *   <li>Empty signature ({@code catalog.events.signature=}) — TRUST the event (in-process
 *       Modulith mode; the producer and consumer share the same JVM). This is the Story 1.5
 *       default.
 *   <li>Non-empty signature — verify HMAC-SHA256 of the canonical envelope
 *       {@code {"consumer":"inventory"}} under the catalog secret. If verification fails, log a
 *       warning and skip the insert.
 * </ul>
 *
 * <p><b>Ponytail:</b> the canonical envelope is intentionally a constant ({@code
 * {"consumer":"inventory"}}), not the event payload. Reasoning: in v1 in-process Modulith mode,
 * the producer and consumer are in the same JVM, so event-content-binding is unnecessary (the
 * Modulith bridge already guarantees in-process delivery). When services split (Story 10.x),
 * the envelope becomes event-content-binding and the signature is fetched from the producer's
 * outbox row (or attached to the Kafka message header).
 *
 * <p><b>Vault-pinned secrets:</b> deferred to a hardening story alongside catalog's Vault wiring
 * (ADR-18). For v1 the dev default in {@code application.yml} is acceptable.
 *
 * <p><b>YAGNI:</b> no {@code @KafkaListener} for {@code catalog.product.created} — intra-JVM in
 * Modulith mode (ADR-01 line 17-86). The {@code @ApplicationModuleListener} invokes when the
 * catalog's {@code outbox} insert fires via {@code ApplicationEventPublisher}. Cross-process
 * delivery (when services split) is Story 10.x; at that point the annotation swaps to
 * {@code @KafkaListener(topic="catalog.product.created")}.
 */
@Component
@RequiredArgsConstructor
public class CatalogEventListener {

  private static final Logger log = LoggerFactory.getLogger(CatalogEventListener.class);

  /** Canonical envelope for HMAC verification — constant for v1 in-process Modulith mode. */
  private static final String CONSUMER_ENVELOPE = "{\"consumer\":\"inventory\"}";

  private final InventoryLedgerEntryRepository ledger;
  private final WarehouseRepository warehouses;

  @Value("${catalog.events.hmac-secret}")
  private String catalogServiceSecret;

  /**
   * The signature the producer attached. Empty string means "trust the event" (in-process
   * Modulith mode); non-empty means verify HMAC-SHA256 of {@link #CONSUMER_ENVELOPE} under
   * {@link #catalogServiceSecret}. Sourced from {@code catalog.events.signature} config.
   */
  @Value("${catalog.events.signature:}")
  private String catalogSignature;

  @ApplicationModuleListener
  @Transactional
  void on(CatalogProductCreated event) {
    if (shouldVerify()) {
      boolean valid =
          HmacEventSigner.verify(CONSUMER_ENVELOPE, catalogSignature, catalogServiceSecret);
      if (!valid) {
        log.warn(
            "HMAC verify failed for CatalogProductCreated productUuid={} — skipping insert",
            event.getProductUuid());
        return;
      }
    }

    Long warehouseId = defaultWarehouseId();
    long ledgerEventId = SnowflakeIdGenerator.generateId();

    try {
      ledger.save(
          InventoryLedgerEntry.builder()
              .variantId(event.getProductUuid())
              .warehouseId(warehouseId)
              .delta(0L)
              .reason("received")
              .eventId(ledgerEventId)
              .build());
    } catch (DataIntegrityViolationException e) {
      // NFR-IDEM-1: at-least-once delivery + idempotent consumer. The unique constraint on
      // inventory_ledger.event_id catches the duplicate; we log and move on.
      log.info(
          "Duplicate ledger insert for productUuid={} ledgerEventId={} — skipping per NFR-IDEM-1",
          event.getProductUuid(),
          ledgerEventId);
    }
  }

  /** True when a non-empty signature is configured — i.e., HMAC verification is enforced. */
  private boolean shouldVerify() {
    return catalogSignature != null && !catalogSignature.isBlank();
  }

  /**
   * Resolve the v1 single-warehouse default. Seeds {@code "HCM-01"} if no warehouse exists yet.
   * ADR-06 single-warehouse v1 default — Story 1.7 expands to multi-warehouse.
   */
  private Long defaultWarehouseId() {
    List<Warehouse> active = warehouses.findByIsActiveTrueAndIsDeletedFalse();
    if (!active.isEmpty()) {
      return active.get(0).getUuid();
    }
    Warehouse seeded =
        warehouses.save(Warehouse.builder().code("HCM-01").displayName("Ho Chi Minh").build());
    return seeded.getUuid();
  }
}