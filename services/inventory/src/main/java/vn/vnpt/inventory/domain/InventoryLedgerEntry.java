package vn.vnpt.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * InventoryLedgerEntry — Story 1.5 / FR-8, FR-13 (append-only ledger), ADR-12.
 *
 * <p>The ledger is the SOLE source of truth for inventory state. {@code on_hand} is a
 * sum-derivation ({@code SUM(delta) GROUP BY variant_id, warehouse_id}) — NEVER a column on this
 * entity. Mutating {@code on_hand} directly would defeat the double-entry reconciliation
 * property (FR-8). See {@code V001__create_inventory_tables.sql} header for the architectural
 * rationale.
 *
 * <p>Append-only invariant (AC #7): this entity is conceptually append-only. There is no
 * {@code void delete*(...)} method on {@code InventoryLedgerEntryRepository} (ArchUnit
 * boundary test enforces this — see {@code InventoryPackageBoundaryTest}). A future
 * hardening story may add a Postgres {@code BEFORE UPDATE OR DELETE} trigger; for v1 the
 * convention + ArchUnit test are sufficient.
 *
 * <p>The {@code eventId} field is the IMMUTABLE idempotency key. The {@code @Setter} on
 * {@code eventId} is omitted (see {@link AccessLevel#NONE}) so that app code cannot mutate it
 * after construction. Hibernate's reflection-based updates ignore this at the SQL layer;
 * the Java-level enforcement is for app code.
 */
@Entity
@Table(name = "inventory_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class InventoryLedgerEntry extends BaseEntity {

  /**
   * Scalar FK to the variant's Snowflake id. The variant lives in {@code catalog_db}; this is a
   * cross-service reference with NO Postgres FK constraint (cross-database FK is impossible).
   * Per architecture.md line 879-884, cross-module access uses public APIs only; here we only
   * store the id, never resolve to a {@code Variant} entity.
   */
  @Column(name = "variant_id", nullable = false)
  private Long variantId;

  /** Scalar FK to {@code warehouses.uuid}; the DDL FK enforces referential integrity. */
  @Column(name = "warehouse_id", nullable = false)
  private Long warehouseId;

  /**
   * Signed delta. {@code +N} for inbound (receive), {@code -N} for outbound (reserve/allocate/
   * ship). Negative deltas are valid for {@code "adjust"} reasons (e.g. lost-in-warehouse).
   * Story 1.6's reservation path is the canonical oversell guard, NOT this entity.
   */
  @Column(name = "delta", nullable = false)
  private Long delta;

  /**
   * Reason string (lowercase). Values are the {@link InventoryReason} enum's
   * {@code toColumnValue()}: {@code "receive"}, {@code "adjust"}, {@code "reserve"},
   * {@code "release"}, {@code "allocate"}, {@code "ship"}. Mapped as a {@code String} (not
   * native Postgres enum) so adding a new reason is a code change, not a migration.
   */
  @Column(name = "reason", nullable = false, length = 64)
  private String reason;

  /**
   * Immutable idempotency key — the Snowflake id of the outbox row that caused this entry.
   * NEVER updated after insert (DB-level: column has no UPDATE trigger by convention; JPA-level:
   * no setter via {@link AccessLevel#NONE}).
   */
  @Setter(AccessLevel.NONE)
  @Column(name = "event_id", nullable = false, updatable = false)
  private Long eventId;

  /**
   * Single-tenant default ({@code "default"}) — architecture-detail.md line 78. Set in
   * {@link #onPrePersist()} if null at insert time.
   */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /**
   * Sets the {@code tenant_id} to {@code "default"} if null at insert time. This mirrors
   * Story 1.2's per-tenant convention but lets tests seed non-default tenants for the
   * multi-tenant activation story (5.x).
   */
  @PrePersist
  public void onPrePersist() {
    if (tenantId == null) {
      tenantId = "default";
    }
  }
}