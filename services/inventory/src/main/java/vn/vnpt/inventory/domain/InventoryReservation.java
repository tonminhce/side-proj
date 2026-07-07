package vn.vnpt.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;
import vn.vnpt.inventory.domain.annotation.IgnoreSoftUkAudit;

/**
 * InventoryReservation — Story 1.6 / FR-9, ADR-12 (DI-01 root-cause fix).
 *
 * <p>A NEW aggregate (FR-9): it tracks held stock separately from the {@code inventory_ledger}
 * source-of-truth. The reservation has a finite TTL; the {@link
 * vn.vnpt.inventory.application.ReservationSweeperJob} expires stale ACTIVE rows and emits
 * {@code inventory.released} events.
 *
 * <p>Lifecycle: {@code ACTIVE → RELEASED} (saga cancel or sweeper) or {@code ACTIVE → COMMITTED}
 * (future Story 4.1 saga step). The {@link #status} field drives the lifecycle; rows are NEVER
 * deleted (append-only invariant, ArchUnit boundary-tested via
 * {@code InventoryPackageBoundaryTest.inventory_reservation_isTerminalOnly}).
 *
 * <p>{@code sagaStepId} is the ADR-11 idempotency key, immutable after insert via
 * {@link AccessLevel#NONE} on the setter — mirroring Story 1.5's {@code InventoryLedgerEntry.eventId}
 * pattern.
 *
 * <p>The {@code status} field uses {@code @Enumerated(EnumType.STRING)} because the column is
 * {@code VARCHAR(32)} — Hibernate maps the enum constant name (e.g., {@code ACTIVE}) directly.
 * Adding a new status is a code change, not a migration — same convention as
 * {@link InventoryReason}.
 *
 * <p><b>Story 1.8 / FR-12:</b> {@code @IgnoreSoftUkAudit} — reservations are terminal-only
 * by convention (status transitions, never row soft-delete). No soft-uniqueness invariant
 * applies.
 */
@Entity
@Table(name = "inventory_reservation")
@IgnoreSoftUkAudit
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class InventoryReservation extends BaseEntity {

  /** Cross-service reference to catalog (no FK — catalog_db is a separate database, ADR-03). */
  @Column(name = "variant_id", nullable = false)
  private Long variantId;

  /** FK to {@code warehouses.uuid}; DDL enforces referential integrity. */
  @Column(name = "warehouse_id", nullable = false)
  private Long warehouseId;

  /** Reserved units. CHECK constraint enforces {@code > 0} at the DB level. */
  @Column(name = "quantity", nullable = false)
  private Long quantity;

  /**
   * Lifecycle status. {@code @Enumerated(EnumType.STRING)} maps to {@code VARCHAR(32)} — the
   * column value is the enum constant's {@link Enum#name()}. See {@link ReservationStatus}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private ReservationStatus status;

  /**
   * Reservation expiry timestamp. Set by the use case (NOT via {@code @PrePersist}) — the TTL
   * is business logic ({@code Instant.now().plus(ttl)}), not a framework default.
   */
  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /**
   * ADR-11 idempotency key. Stable across saga retries; the
   * {@code uq_inventory_reservation_saga_step} UNIQUE constraint catches duplicate inserts.
   * Immutable after insert — no setter generated.
   */
  @Setter(AccessLevel.NONE)
  @Column(name = "saga_step_id", nullable = false, updatable = false, length = 128)
  private String sagaStepId;

  /** Optional FK to the order (cross-service; no constraint). Saga fills when known. */
  @Column(name = "order_uuid")
  private Long orderUuid;

  /** Single-tenant default ({@code "default"}) — architecture-detail.md line 78. */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /**
   * Sets the {@code status} to {@link ReservationStatus#ACTIVE} and {@code tenantId} to
   * {@code "default"} if null at insert time. Mirrors {@link InventoryLedgerEntry#onPrePersist()}.
   */
  @PrePersist
  public void onPrePersist() {
    if (status == null) {
      status = ReservationStatus.ACTIVE;
    }
    if (tenantId == null) {
      tenantId = "default";
    }
  }
}