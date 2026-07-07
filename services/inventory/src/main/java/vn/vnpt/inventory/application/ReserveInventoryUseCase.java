package vn.vnpt.inventory.application;

import java.time.Duration;
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
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.event.InventoryLifecycleEvent;
import vn.vnpt.inventory.domain.event.LifecyclePhase;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;
import vn.vnpt.inventory.infrastructure.outbox.LifecycleEventPublisher;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/** Atomic SELECT FOR UPDATE reservation. Story 1.6+ — see ADR-04/-11/-12/-20. */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ReserveInventoryUseCase {

  private final InventoryReservationRepository reservationRepository;
  private final InventoryLedgerEntryRepository ledgerRepository;
  private final WarehouseRepository warehouseRepository;
  private final LifecycleEventPublisher lifecycleEventPublisher;
  private final ObjectMapper objectMapper;
  private final PickWarehouseForReservationUseCase pickWarehouseUseCase;

  @Value("${inventory.events.hmac-secret}")
  private String inventoryServiceSecret;

  @Value("${inventory.reservation.ttl-minutes:15}")
  private long defaultTtlMinutes;

  /**
   * Reserve {@code quantity} units for a saga step. Idempotent on {@code sagaStepId}: same
   * {@code saga_step_id} returns the existing reservation with no side-effects.
   */
  public InventoryReservation reserve(ReserveInventoryCommand cmd) {
    validate(cmd);
    validateWarehouseDispatch(cmd);

    Long pickerPick =
        cmd.warehouseId() == null
            ? pickWarehouseUseCase
                .pickWarehouseId(cmd.variantId(), cmd.shippingRegion(), cmd.quantity())
                .orElseThrow(
                    () ->
                        new InsufficientStockException(
                            cmd.variantId(), null, cmd.quantity(), 0L))
            : null;
    final Long resolvedWarehouseId = cmd.warehouseId() != null ? cmd.warehouseId() : pickerPick;

    warehouseRepository
        .findById(resolvedWarehouseId)
        .orElseThrow(() -> new WarehouseNotFoundException(resolvedWarehouseId));

    var existing = reservationRepository.findBySagaStepId(cmd.sagaStepId());
    if (existing.isPresent()) {
      return existing.get();
    }

    ledgerRepository.lockLedgerByVariantAndWarehouse(cmd.variantId(), resolvedWarehouseId);

    var existingAfterLock = reservationRepository.findBySagaStepId(cmd.sagaStepId());
    if (existingAfterLock.isPresent()) {
      return existingAfterLock.get();
    }

    long available =
        ledgerRepository
            .findAvailable(cmd.variantId(), resolvedWarehouseId)
            .map(AvailableStockView::available)
            .orElse(0L);

    if (available < cmd.quantity()) {
      throw new InsufficientStockException(
          cmd.variantId(), resolvedWarehouseId, cmd.quantity(), available);
    }

    Duration ttl = cmd.ttl() != null ? cmd.ttl() : Duration.ofMinutes(defaultTtlMinutes);
    Instant expiresAt = Instant.now().plus(ttl);
    // ponytail: expiresAt is captured on the reservation row only — the unified lifecycle
    // event does not carry it (per AC #5 shape). Consumers wanting TTL can join the
    // reservation table by reservationUuid.

    InventoryReservation reservation =
        reservationRepository.save(
            InventoryReservation.builder()
                .variantId(cmd.variantId())
                .warehouseId(resolvedWarehouseId)
                .quantity(cmd.quantity())
                .status(ReservationStatus.ACTIVE)
                .expiresAt(expiresAt)
                .sagaStepId(cmd.sagaStepId())
                .orderUuid(cmd.orderUuid())
                .tenantId("default")
                .build());

    long ledgerEventId = SnowflakeIdGenerator.generateId();
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(cmd.variantId())
            .warehouseId(resolvedWarehouseId)
            .delta(-cmd.quantity())
            .reason(InventoryReason.RESERVE.toColumnValue())
            .eventId(ledgerEventId)
            .tenantId("default")
            .build());

    InventoryLifecycleEvent payload =
        InventoryLifecycleEvent.builder()
            .eventId(ledgerEventId)
            .aggregateType("InventoryReservation")
            .aggregateId(reservation.getUuid())
            .occurredAt(Instant.now())
            .phase(LifecyclePhase.RESERVED)
            .reservationUuid(reservation.getUuid())
            .variantId(cmd.variantId())
            .warehouseId(resolvedWarehouseId)
            .quantity(cmd.quantity())
            .reason(InventoryReason.RESERVE.wireValue())
            .sagaStepId(cmd.sagaStepId())
            .orderUuid(cmd.orderUuid())
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
            .reservationUuid(payload.getReservationUuid())
            .variantId(payload.getVariantId())
            .warehouseId(payload.getWarehouseId())
            .quantity(payload.getQuantity())
            .reason(payload.getReason())
            .sagaStepId(payload.getSagaStepId())
            .orderUuid(payload.getOrderUuid())
            .tenantId(payload.getTenantId())
            .signatures(Map.of("hmac_sha256", signature))
            .build();

    lifecycleEventPublisher.publish(signed);

    log.debug(
        "Reserved: variantId={} warehouseId={} qty={} sagaStepId={} reservationUuid={}",
        cmd.variantId(),
        resolvedWarehouseId,
        cmd.quantity(),
        cmd.sagaStepId(),
        reservation.getUuid());

    return reservation;
  }

  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }

  private static void validate(ReserveInventoryCommand cmd) {
    if (cmd.quantity() <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    if (cmd.sagaStepId() == null || cmd.sagaStepId().isBlank()) {
      throw new IllegalArgumentException("sagaStepId is required");
    }
    if (cmd.ttl() != null && (cmd.ttl().isNegative() || cmd.ttl().isZero())) {
      throw new IllegalArgumentException("ttl must be positive");
    }
  }

  private static void validateWarehouseDispatch(ReserveInventoryCommand cmd) {
    boolean hasWarehouseId = cmd.warehouseId() != null;
    boolean hasRegion = cmd.shippingRegion() != null;
    if (!hasWarehouseId && !hasRegion) {
      throw new IllegalArgumentException("warehouseId or shippingRegion required");
    }
    if (hasWarehouseId && hasRegion) {
      throw new IllegalArgumentException("exactly one of warehouseId, shippingRegion required");
    }
  }
}