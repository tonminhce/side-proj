package vn.vnpt.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * Warehouse aggregate — Story 1.5 / FR-8, ADR-06 (single-warehouse v1 default).
 *
 * <p>Represents a physical warehouse. The {@code code} field is the admin-managed slug
 * ({@code "HCM-01"}, {@code "HN-01"}) used in admin UIs and API contracts; the {@code uuid}
 * (Snowflake {@code Long}) is the FK target from {@code inventory_ledger.warehouse_id}.
 *
 * <p>The {@code code} unique constraint is declared in the DDL (V001), NOT via JPA
 * {@code @UniqueConstraint} — mirrors Story 1.2's pattern. See architecture.md line 595.
 */
@Entity
@Table(name = "warehouses")
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
}