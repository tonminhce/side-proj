package vn.vnpt.inventory.application;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.event.InventoryLifecycleEvent;
import vn.vnpt.inventory.domain.event.LifecyclePhase;
import vn.vnpt.inventory.domain.exception.ReservationNotFoundException;
import vn.vnpt.inventory.infrastructure.outbox.LifecycleEventPublisher;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * AllocateInventoryUseCase — Story 1.8 / FR-11 (ALLOCATED phase).
 *
 * <p>Promotes an ACTIVE reservation to COMMITTED (a saga step invoked by OrderService after
 * successful payment authorization in Story 2.5). The allocation:
 *
 * <ol>
 *   <li>Loads the reservation by {@code uuid}. Missing → {@link ReservationNotFoundException}.
 *   <li>Terminal-state guard: {@code status != ACTIVE} → log + no-op (idempotency on
 *       already-committed or already-released reservations).
 *   <li>Appends an {@code inventory_ledger} row with {@code reason='allocate', delta=-quantity}.
 *   <li>Sets {@code reservation.status = COMMITTED}.
 *   <li>Emits an {@link InventoryLifecycleEvent} with {@code phase = ALLOCATED} via the
 *       {@link LifecycleEventPublisher}.
 * </ol>
 *
 * <p>Idempotency: the saga retries with the same {@code saga_step_id} hit the terminal-state
 * guard (no-op on COMMITTED). A future Story 4.x adds an explicit
 * {@code (reservationUuid, sagaStepId)} UNIQUE for stronger guarantee; not in v1.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class AllocateInventoryUseCase {

  private final InventoryReservationRepository reservationRepository;
  private final InventoryLedgerEntryRepository ledgerRepository;
  private final LifecycleEventPublisher lifecycleEventPublisher;
  private final ObjectMapper objectMapper;

  @Value("${inventory.events.hmac-secret}")
  private String inventoryServiceSecret;

  public InventoryReservation allocate(AllocateInventoryCommand cmd) {
    if (cmd.reservationUuid() == null) {
      throw new IllegalArgumentException("reservationUuid is required");
    }
    if (cmd.sagaStepId() == null || cmd.sagaStepId().isBlank()) {
      throw new IllegalArgumentException("sagaStepId is required");
    }

    InventoryReservation reservation =
        reservationRepository
            .findById(cmd.reservationUuid())
            .orElseThrow(() -> new ReservationNotFoundException(cmd.reservationUuid()));

    // Terminal-state guard: idempotent re-allocate is a no-op (matches Release's pattern).
    if (reservation.getStatus() != null && reservation.getStatus().isTerminal()) {
      log.debug(
          "allocate: reservationUuid={} already terminal (status={}); no-op",
          reservation.getUuid(),
          reservation.getStatus());
      return reservation;
    }

    long ledgerEventId = SnowflakeIdGenerator.generateId();
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(reservation.getVariantId())
            .warehouseId(reservation.getWarehouseId())
            .delta(-reservation.getQuantity())
            .reason(InventoryReason.ALLOCATE.toColumnValue())
            .eventId(ledgerEventId)
            .tenantId("default")
            .build());

    reservation.setStatus(ReservationStatus.COMMITTED);
    reservationRepository.save(reservation);

    // ponytail: build the payload WITHOUT signatures first so we can canonicalize + sign,
    // then re-build WITH the signatures attached. The HMAC envelope is computed over the
    // business payload (signatures is a security envelope, not part of the business data).
    InventoryLifecycleEvent payload =
        InventoryLifecycleEvent.builder()
            .eventId(ledgerEventId)
            .aggregateType("InventoryReservation")
            .aggregateId(reservation.getUuid())
            .occurredAt(Instant.now())
            .phase(LifecyclePhase.ALLOCATED)
            .reservationUuid(reservation.getUuid())
            .variantId(reservation.getVariantId())
            .warehouseId(reservation.getWarehouseId())
            .quantity(reservation.getQuantity())
            .reason(InventoryReason.ALLOCATE.wireValue())
            .sagaStepId(cmd.sagaStepId())
            .orderUuid(reservation.getOrderUuid())
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
        "Allocated: reservationUuid={} variantId={} warehouseId={} qty={}",
        reservation.getUuid(),
        reservation.getVariantId(),
        reservation.getWarehouseId(),
        reservation.getQuantity());

    return reservation;
  }

  /** Convert to {@code Map<String, Object>} then JCS-canonicalize — mirrors Story 1.6 producer. */
  private String canonicalize(Object payload) {
    Map<String, Object> map = objectMapper.convertValue(payload, Map.class);
    return JcsCanonicalJson.serialize(map);
  }
}