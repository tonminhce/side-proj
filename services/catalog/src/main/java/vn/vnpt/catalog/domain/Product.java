package vn.vnpt.catalog.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * Product aggregate root — Story 1.2 / FR-1, FR-2, FR-4.
 *
 * <p>Owns the product-level identity (slug/SKU) but NOT price — price lives on {@link Variant}
 * per {@code epics.md} line 472. Variants are accessed via {@code VariantRepository} (cross-aggregate
 * navigation through JPA associations is forbidden, architecture.md line 879).
 *
 * <p>Inherits {@code uuid} (Snowflake {@code Long}, NOT {@code UUID}) and audit fields from
 * {@link BaseEntity} / {@link RootEntity}. The {@code uuid} column is named {@code uuid} for
 * historical reasons; its value is a Snowflake {@code Long}.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(
    name = "id",
    column = @Column(name = "id", insertable = false, updatable = false))
public class Product extends BaseEntity {

  @Column(nullable = false, length = 255)
  private String name;

  @Column(nullable = false, unique = true, length = 64)
  private String sku;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(length = 128)
  private String brand;

  /**
   * tenant_id — V002 migration adds the column; v1 default is {@code 'default'} per
   * architecture-detail.md line 78. The read endpoint filters on this column; writes inherit
   * the {@code 'default'} default from V002's {@code DEFAULT 'default'}.
   */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /**
   * Variants accessible for the admin read view (Story 1.4 / FR-6). Unidirectional —
   * {@code @JoinColumn} on the parent's side because the FK lives on {@code variants} (V001
   * DDL: {@code product_uuid BIGINT NOT NULL REFERENCES products(uuid)}). Variant does NOT
   * hold a {@code @ManyToOne} back-pointer (architecture.md line 879 — cross-aggregate
   * navigation through JPA associations is forbidden; VariantRepository is the port).
   *
   * <p>Lazy fetch avoids loading variants on every product read (writes are entity-load-only).
   * The list query (Story 1.4) uses a JPQL {@code JOIN FETCH} to load eagerly in one query.
   */
  @OneToMany(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_uuid")
  @Builder.Default
  private List<Variant> variants = new ArrayList<>();

  /** Domain factory — does NOT persist (use case's job). */
  public static Product create(String name, String sku, String description, String brand) {
    return Product.builder().name(name).sku(sku).description(description).brand(brand).tenantId("default").build();
  }
}
