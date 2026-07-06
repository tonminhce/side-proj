package vn.vnpt.catalog.domain;

import jakarta.persistence.AttributeOverride;
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
 * Attribute definition aggregate — Story 1.2 / FR-1, FR-2, FR-4.
 *
 * <p>Canonical attribute definition per product (e.g. {@code name="color"}, {@code
 * displayName="Color"}). Distinct from {@link Variant#getAttributes()}, which is the per-variant
 * selection in JSONB.
 *
 * <p>The {@code (product_uuid, name)} UNIQUE constraint lives in V001 DDL; the JPA mapping does
 * not redeclare it — {@code spring.jpa.hibernate.ddl-auto=validate} enforces the match at boot.
 */
@Entity
@Table(name = "attributes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(
    name = "id",
    column = @Column(name = "id", insertable = false, updatable = false))
public class Attribute extends BaseEntity {

  @Column(name = "product_uuid", nullable = false)
  private Long productUuid;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "display_name", nullable = false, length = 128)
  private String displayName;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;
}
