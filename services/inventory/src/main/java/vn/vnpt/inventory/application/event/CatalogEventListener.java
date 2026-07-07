package vn.vnpt.inventory.application.event;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.catalog.domain.event.CatalogProductCreated;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;

/** In-process consumer of catalog events. Story 1.5 (first consumer) / 1.6 (HMAC verify). */
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
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  void on(CatalogProductCreated event) {
    log.debug("CatalogEventListener.on called: productUuid={}", event.getProductUuid());
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
   * ADR-06 single-warehouse v1 default — Story 1.7 expanded to multi-warehouse; the listener
   * seeds HCM-01 with region=SOUTH (the v1 default). V005's seeds may have already inserted it;
   * the {@code save} is a no-op via {@code uq_warehouses_code} when the row exists.
   */
  private Long defaultWarehouseId() {
    List<Warehouse> active = warehouses.findByIsActiveTrueAndIsDeletedFalse();
    if (!active.isEmpty()) {
      return active.get(0).getUuid();
    }
    Warehouse seeded =
        warehouses.save(
            Warehouse.builder()
                .code("HCM-01")
                .displayName("Ho Chi Minh")
                .region(vn.vnpt.inventory.domain.Region.SOUTH)
                .build());
    return seeded.getUuid();
  }
}