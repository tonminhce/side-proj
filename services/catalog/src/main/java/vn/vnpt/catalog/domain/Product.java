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

  /** Domain factory — does NOT persist (use case's job). */
  public static Product create(String name, String sku, String description, String brand) {
    return Product.builder().name(name).sku(sku).description(description).brand(brand).build();
  }
}
