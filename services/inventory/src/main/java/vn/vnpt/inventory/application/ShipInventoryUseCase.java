package vn.vnpt.inventory.application;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.inventory.application.query.AvailableStockView;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.event.InventoryLifecycleEvent;
import vn.vnpt.inventory.domain.event.LifecyclePhase;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;
import vn.vnpt.inventory.infrastructure.outbox.LifecycleEventPublisher;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * ShipInventoryUseCase — Story 1.8 / FR-11 (SHIPPED phase).
 *
 * <p>Warehouse-level shipping: subtracts {@code quantity} units from a (variant, warehouse)
 * pair and emits an {@link InventoryLifecycleEvent} with {@code phase = SHIPPED}. The
 * canonical authoritative gate (FOR UPDATE row lock) lives in {@code ReserveInventoryUseCase};
 * this use case does a pre-flight {@link OnHandUseCase#findAvailable} check and trusts the
 * caller (the saga) to be the gating step.
 *
 * <p>YAGNI: no multi-line carrier integration (FR-35/36/37/38/39 live in Epic 4). No
 * {@code shipped_at} column on the ledger — the event's {@code occurredAt} captures it.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ShipInventoryUseCase {

  private final WarehouseRepository warehouseRepository;
  private final InventoryLedgerEntryRepository ledgerRepository;
  private final OnHandUseCase onHandUseCase;
  private final LifecycleEventPublisher lifecycleEventPublisher;
  private final ObjectMapper objectMapper;

  @Value("${inventory.events.hmac-secret}")
  private String inventoryServiceSecret;

  public InventoryLedgerEntry ship(ShipInventoryCommand cmd) {
    if (cmd.variantId() == null) {
      throw new IllegalArgumentException("variantId is required");
    }
    if (cmd.warehouseId() == null) {
      throw new IllegalArgumentException("warehouseId is required");
    }
    if (cmd.quantity() <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    if (cmd.sagaStepId() == null || cmd.sagaStepId().isBlank()) {
      throw new IllegalArgumentException("sagaStepId is required");
    }

    warehouseRepository
        .findById(cmd.warehouseId())
        .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

    long available =
        onHandUseCase
            .findAvailable(cmd.variantId(), cmd.warehouseId())
            .map(AvailableStockView::available)
            .orElse(0L);

    if (available < cmd.quantity()) {
      throw new InsufficientStockException(
          cmd.variantId(), cmd.warehouseId(), cmd.quantity(), available);
    }

    long ledgerEventId = SnowflakeIdGenerator.generateId();
    InventoryLedgerEntry entry =
        ledgerRepository.save(
            InventoryLedgerEntry.builder()
                .variantId(cmd.variantId())
                .warehouseId(cmd.warehouseId())
                .delta(-cmd.quantity())
                .reason(InventoryReason.SHIP.toColumnValue())
                .eventId(ledgerEventId)
                .tenantId("default")
                .build());

    InventoryLifecycleEvent payload =
        InventoryLifecycleEvent.builder()
            .eventId(ledgerEventId)
            .aggregateType("InventoryLedger")
            .aggregateId(entry.getUuid())
            .occurredAt(Instant.now())
            .phase(LifecyclePhase.SHIPPED)
            .variantId(cmd.variantId())
            .warehouseId(cmd.warehouseId())
            .quantity(cmd.quantity())
            .reason(InventoryReason.SHIP.wireValue())
            .sagaStepId(cmd.sagaStepId())
            .tenantId("default")
            .build();

    String signature = HmacEventSigner.sign(canonicalize(payload), inventoryServiceSecret);

    InventoryLifecycleEvent signed =
        InventoryLifecycleEvent.builder()
            .eventId(payload.getEventId())
            .aggregateType(payload.getAggregateType())
            .aggregateId(payload.getAggregateId())
            .occurredAt(payload.getOccurredAt())
            .phase(payload.getPhase())
            .variantId(payload.getVariantId())
            .warehouseId(payload.getWarehouseId())
            .quantity(payload.getQuantity())
            .reason(payload.getReason())
            .sagaStepId(payload.getSagaStepId())
            .tenantId(payload.getTenantId())
            .signatures(Map.of("hmac_sha256", signature))
            .build();

    lifecycleEventPublisher.publish(signed);

    log.debug(
        "Shipped: variantId={} warehouseId={} qty={} sagaStepId={}",
        cmd.variantId(),
        cmd.warehouseId(),
        cmd.quantity(),
        cmd.sagaStepId());

    return entry;
  }

  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }
}