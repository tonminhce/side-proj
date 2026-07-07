package vn.vnpt.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.cart.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * CartLine — one row per {@code (cart, variant)} pair (Story 2.1 / FR-15).
 *
 * <p>{@code @IgnoreSoftUkAudit} — the DB-level UNIQUE on {@code (cart_uuid, variant_id)} is the
 * source of truth; a {@code @SoftUk} would be redundant. CartLines are operationally soft-deleted by
 * {@code RemoveLineUseCase} but never re-created (a removed line becomes a new line row on next add).
 *
 * <p>{@code sellerId} is a nullable ADR-07 marketplace v2 placeholder (null in v1 B2C).
 * {@code variantId} is a cross-service reference with NO FK (catalog_db is a separate database,
 * ADR-03). {@code @Version version} gives per-line optimistic concurrency (FR-16).
 */
@Entity
@Table(name = "cart_lines")
@IgnoreSoftUkAudit
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class CartLine extends BaseEntity {

  /** FK to {@code carts.uuid} (same-service). */
  @Column(name = "cart_uuid", nullable = false)
  private Long cartUuid;

  /** Single-tenant default ({@code "default"}). */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /** ADR-07 marketplace v2 placeholder; null in v1 B2C. */
  @Column(name = "seller_id", length = 64)
  private String sellerId;

  /** Cross-service reference to catalog (no FK — ADR-03). */
  @Column(name = "variant_id", nullable = false)
  private Long variantId;

  /** Line quantity; DB CHECK enforces {@code > 0}. */
  @Column(name = "quantity", nullable = false)
  private Integer quantity;

  /** Optimistic-concurrency version (FR-16). */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** Defaults {@code tenantId = "default"} at insert time. */
  @PrePersist
  public void onPrePersist() {
    if (tenantId == null) {
      tenantId = "default";
    }
  }
}
