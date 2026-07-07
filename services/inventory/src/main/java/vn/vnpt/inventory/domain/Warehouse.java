package vn.vnpt.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;

/**
 * Warehouse aggregate — Story 1.5 / FR-8, ADR-06 (single-warehouse v1 default). Extended in
 * Story 1.7 / FR-10 with a {@code region} routing key. Story 1.8 / FR-12 adds {@code @SoftUk}
 * to enforce soft-delete uniqueness at the application layer.
 *
 * <p>Represents a physical warehouse. The {@code code} field is the admin-managed slug
 * ({@code "HCM-01"}, {@code "HN-01"}) used in admin UIs and API contracts; the {@code uuid}
 * (Snowflake {@code Long}) is the FK target from {@code inventory_ledger.warehouse_id}.
 *
 * <p>The {@code code} unique constraint is declared in the DDL (V001), NOT via JPA
 * {@code @UniqueConstraint} — mirrors Story 1.2's pattern. See architecture.md line 595.
 *
 * <p><b>Story 1.8 / FR-12 / DI-09 fix:</b> {@code @SoftUk(name = "warehouse_code_per_tenant",
 * fields = {"tenantId", "code"})} enforces soft-delete uniqueness via util's
 * {@code UkValidator}. The natural key is {@code (tenantId, code)} — a hard-deleted
 * warehouse can be replaced by a new warehouse with the same code in a different tenant.
 * The existing DB constraint {@code uq_warehouses_code} is column-only (not {@code (tenant_id,
 * code)}); v1 is single-tenant ({@code tenant_id = 'default'}) so the application-layer
 * check is equivalent. Future Story 8.x (multi-tenant activation per ADR-01) tightens the
 * DB constraint to {@code uq_warehouses_tenant_code(tenant_id, code)} and removes this
 * application-layer check.
 */
@Entity
@Table(name = "warehouses")
@SoftUk(name = "warehouse_code_per_tenant", fields = {"tenantId", "code"})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class Warehouse extends BaseEntity {

  /** Admin-managed slug. Unique. */
  @Column(nullable = false, length = 64, unique = true)
  private String code;

  /** Human-readable name for the admin UI. */
  @Column(name = "display_name", nullable = false, length = 255)
  private String displayName;

  /**
   * FR-10 dispatch key. Hibernate maps the enum constant's {@code name()} directly to the
   * VARCHAR(16) column ({@code NORTH}, {@code SOUTH}, {@code CENTRAL}). The DB-level CHECK
   * constraint {@code chk_warehouses_region} enforces the same set. Adding a region is a
   * code change, not a migration.
   */
  @Column(name = "region", nullable = false, length = 16)
  @Enumerated(EnumType.STRING)
  private Region region;

  /**
   * Tenant id — Story 1.8 / FR-12: included in the {@code @SoftUk(fields = {"tenantId",
   * "code"})} so the soft-delete uniqueness invariant spans both columns. v1 is single-tenant
   * ({@code tenantId = "default"}); future Story 8.x (multi-tenant activation per ADR-01)
   * tightens the DB constraint to {@code uq_warehouses_tenant_code(tenant_id, code)}.
   */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /** Sets {@code tenantId} to {@code "default"} if null at insert time. */
  @PrePersist
  public void onPrePersist() {
    if (tenantId == null) {
      tenantId = "default";
    }
  }
}