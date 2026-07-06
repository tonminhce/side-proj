package vn.vnpt.inventory.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.event.InventoryReleased;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * ReleaseInventoryUseCase — Story 1.6 / FR-9 (DI-01 root-cause fix), ADR-04, ADR-11.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #release(String)} — saga-initiated (cart cancelled, saga timeout). Story 2.5
 *       calls this from the checkout saga.
 *   <li>{@link #releaseExpired(Long)} — sweeper-initiated (TTL expiry). Each invocation is its
 *       own transaction ({@code Propagation.REQUIRES_NEW}) so a slow release on one reservation
 *       doesn't poison the rest of the sweeper batch.
 * </ul>
 *
 * <p>Both paths are idempotent on {@code saga_step_id}: a re-release on an already-released
 * reservation hits the terminal-state guard and no-ops.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ReleaseInventoryUseCase {

  private final InventoryReservationRepository reservationRepository;
  private final InventoryLedgerEntryRepository ledgerRepository;
  private final OutboxPublisher outbox;
  private final ObjectMapper objectMapper;

  @Value("${inventory.events.hmac-secret}")
  private String inventoryServiceSecret;

  /**
   * Release a reservation by {@code saga_step_id} (saga-initiated). Idempotent: unknown or
   * terminal-state reservations log + return without error.
   */
  @Transactional
  public void release(String sagaStepId) {
    Optional<InventoryReservation> opt =
        reservationRepository.findBySagaStepId(sagaStepId);
    if (opt.isEmpty()) {
      log.debug("release: saga_step_id={} not found; no-op", sagaStepId);
      return;
    }
    InventoryReservation reservation = opt.get();
    doRelease(reservation);
  }

  /**
   * Release an expired reservation by uuid (sweeper-initiated). Each call is its own
   * transaction ({@code Propagation.REQUIRES_NEW}) so the sweeper loop can keep iterating
   * even if one release throws.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void releaseExpired(Long reservationUuid) {
    Optional<InventoryReservation> opt = reservationRepository.findById(reservationUuid);
    if (opt.isEmpty()) {
      log.debug("releaseExpired: reservationUuid={} not found; no-op", reservationUuid);
      return;
    }
    InventoryReservation reservation = opt.get();
    doRelease(reservation);
  }

  private void doRelease(InventoryReservation reservation) {
    // Terminal-state guard: idempotent re-release is a no-op.
    if (reservation.getStatus() != null && reservation.getStatus().isTerminal()) {
      log.debug(
          "release: reservationUuid={} already terminal (status={}); no-op",
          reservation.getUuid(),
          reservation.getStatus());
      return;
    }

    // Append ledger row with reason='release', delta=+quantity (mirror the reservation's negative delta).
    long releaseEventId = SnowflakeIdGenerator.generateId();
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(reservation.getVariantId())
            .warehouseId(reservation.getWarehouseId())
            .delta(reservation.getQuantity())
            .reason(InventoryReason.RELEASE.toColumnValue())
            .eventId(releaseEventId)
            .tenantId("default")
            .build());

    // Status transition (NOT delete; append-only / terminal-only invariant).
    reservation.setStatus(ReservationStatus.RELEASED);
    reservationRepository.save(reservation);

    // Emit outbox event with HMAC signature (ADR-20 producer-side).
    InventoryReleased payload =
        new InventoryReleased(
            reservation.getUuid(),
            reservation.getVariantId(),
            reservation.getWarehouseId(),
            reservation.getQuantity(),
            reservation.getSagaStepId(),
            reservation.getOrderUuid(),
            releaseEventId,
            Instant.now());

    Map<String, String> signatures =
        Map.of("hmac_sha256", HmacEventSigner.sign(canonicalize(payload), inventoryServiceSecret));

    outbox.append(
        "InventoryReservation",
        reservation.getUuid(),
        "inventory.released",
        payload,
        signatures);

    log.debug(
        "Released: variantId={} warehouseId={} qty={} sagaStepId={} reservationUuid={}",
        reservation.getVariantId(),
        reservation.getWarehouseId(),
        reservation.getQuantity(),
        reservation.getSagaStepId(),
        reservation.getUuid());
  }

  /**
   * Convert a record payload to a {@code Map<String, Object>}, then JCS-canonicalize. Mirrors
   * {@code ReserveInventoryUseCase#canonicalize}.
   */
  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }
}