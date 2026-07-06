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
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.application.query.AvailableStockView;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.event.InventoryReserved;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * ReserveInventoryUseCase — Story 1.6 / FR-9 (DI-01 root-cause fix), ADR-04, ADR-11, ADR-12,
 * ADR-20.
 *
 * <p>Atomic per-row reservation with Postgres {@code SELECT … FOR UPDATE}. Steps:
 *
 * <ol>
 *   <li>Validate input (quantity, sagaStepId, ttl).
 *   <li>Validate warehouse exists.
 *   <li><b>ADR-11 idempotency:</b> same {@code saga_step_id} returns the existing reservation.
 *   <li>Acquire {@code FOR UPDATE} lock on {@code inventory_ledger} rows for the pair.
 *   <li>Compute {@code available = onHand - SUM(active reservations)}. If
 *       {@code available < requested}, throw {@link InsufficientStockException} (409).
 *   <li>Insert {@code inventory_reservation} row (status=ACTIVE, expiresAt=now+ttl).
 *   <li>Append {@code inventory_ledger} row with {@code reason='reserve', delta=-qty}.
 *   <li>Emit outbox row with HMAC signature (producer-side ADR-20).
 * </ol>
 *
 * <p>The {@code @Transactional} boundary owns the critical section. ADR-11 idempotency check
 * runs INSIDE this boundary so the check + lock + insert are atomic.
 *
 * <p>The {@code FOR UPDATE} row lock on {@code inventory_ledger} serializes concurrent
 * reservations for the same {@code (variant, warehouse)}. The first commit decrements
 * {@code on_hand} + inserts the reservation; the second's {@code FOR UPDATE} waits, then
 * re-reads and sees the decremented state, returning 409.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ReserveInventoryUseCase {

  private final InventoryReservationRepository reservationRepository;
  private final InventoryLedgerEntryRepository ledgerRepository;
  private final WarehouseRepository warehouseRepository;
  private final OutboxPublisher outbox;
  private final ObjectMapper objectMapper;

  @Value("${inventory.events.hmac-secret}")
  private String inventoryServiceSecret;

  @Value("${inventory.reservation.ttl-minutes:15}")
  private long defaultTtlMinutes;

  /**
   * Reserve {@code quantity} units for a saga step. Idempotent on {@code sagaStepId}: same
   * {@code saga_step_id} returns the existing reservation with no side-effects.
   *
   * @throws IllegalArgumentException for invalid input (quantity, sagaStepId, ttl)
   * @throws WarehouseNotFoundException if {@code warehouseId} does not exist
   * @throws InsufficientStockException if available stock {@code < requested}
   */
  public InventoryReservation reserve(ReserveInventoryCommand cmd) {
    validate(cmd);

    warehouseRepository
        .findById(cmd.warehouseId())
        .orElseThrow(() -> new WarehouseNotFoundException(cmd.warehouseId()));

    // ADR-11 idempotency: same saga_step_id returns the same reservation.
    var existing = reservationRepository.findBySagaStepId(cmd.sagaStepId());
    if (existing.isPresent()) {
      return existing.get();
    }

    // FR-9 lock: serialize concurrent reserves for the same (variant, warehouse).
    ledgerRepository.lockLedgerByVariantAndWarehouse(cmd.variantId(), cmd.warehouseId());

    // ADR-11 idempotency re-check inside the lock: a concurrent request with the same
    // saga_step_id may have committed between our pre-lock check and lock acquisition.
    // Without this re-check, the second request would see insufficient stock (because the
    // first reserved the qty) and throw InsufficientStockException — breaking ADR-11's
    // "saga retries with same step get the same result" guarantee.
    var existingAfterLock = reservationRepository.findBySagaStepId(cmd.sagaStepId());
    if (existingAfterLock.isPresent()) {
      return existingAfterLock.get();
    }

    // After lock: compute available. Both reads see committed state of any concurrent
    // transaction (the FOR UPDATE serializes them).
    long available =
        ledgerRepository
            .findAvailable(cmd.variantId(), cmd.warehouseId())
            .map(AvailableStockView::available)
            .orElse(0L);

    if (available < cmd.quantity()) {
      throw new InsufficientStockException(
          cmd.variantId(), cmd.warehouseId(), cmd.quantity(), available);
    }

    Duration ttl = cmd.ttl() != null ? cmd.ttl() : Duration.ofMinutes(defaultTtlMinutes);
    Instant expiresAt = Instant.now().plus(ttl);

    InventoryReservation reservation =
        reservationRepository.save(
            InventoryReservation.builder()
                .variantId(cmd.variantId())
                .warehouseId(cmd.warehouseId())
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
            .warehouseId(cmd.warehouseId())
            .delta(-cmd.quantity())
            .reason(InventoryReason.RESERVE.toColumnValue())
            .eventId(ledgerEventId)
            .tenantId("default")
            .build());

    InventoryReserved payload =
        new InventoryReserved(
            reservation.getUuid(),
            cmd.variantId(),
            cmd.warehouseId(),
            cmd.quantity(),
            cmd.sagaStepId(),
            cmd.orderUuid(),
            ledgerEventId,
            expiresAt,
            Instant.now());

    Map<String, String> signatures =
        Map.of("hmac_sha256", HmacEventSigner.sign(canonicalize(payload), inventoryServiceSecret));

    outbox.append(
        "InventoryReservation",
        reservation.getUuid(),
        "inventory.reserved",
        payload,
        signatures);

    log.debug(
        "Reserved: variantId={} warehouseId={} qty={} sagaStepId={} reservationUuid={}",
        cmd.variantId(),
        cmd.warehouseId(),
        cmd.quantity(),
        cmd.sagaStepId(),
        reservation.getUuid());

    return reservation;
  }

  /**
   * Convert a record payload to a {@code Map<String, Object>}, then JCS-canonicalize. Same
   * parse-then-canonicalize pattern as the catalog producer — both producer and consumer apply
   * the same chain so the HMAC envelope is independent of Jackson whitespace quirks.
   */
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
}