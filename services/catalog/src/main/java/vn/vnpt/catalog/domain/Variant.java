package vn.vnpt.catalog.domain;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * Variant aggregate — Story 1.2 / FR-1, FR-2, FR-4.
 *
 * <p>A variant is one sellable SKU of a {@link Product}, keyed by a stable hash-derived SKU.
 * New attributes can be added without a schema change because {@link #attributes} is a JSONB
 * column ({@code epics.md} line 472).
 *
 * <p>SKU stability invariant ({@code VariantTest.sku_isStableAcrossInstances}): {@link
 * #computeSku(String, Map)} called twice in different JVMs produces the byte-identical string.
 * The deterministic canonical form sorts keys lexicographically and joins {@code key=value}
 * pairs with {@code |} — only this ordering is allowed.
 */
@Entity
@Table(name = "variants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
@AttributeOverride(
    name = "id",
    column = @Column(name = "id", insertable = false, updatable = false))
public class Variant extends BaseEntity {

  /** Scalar FK to {@code products.uuid}; the DDL FK enforces referential integrity. The JPA
   * mapping uses {@code Long} (not a {@code @ManyToOne}) per architecture.md line 879. */
  @Column(name = "product_uuid", nullable = false)
  private Long productUuid;

  @Column(nullable = false, unique = true, length = 64)
  private String sku;

  /** JSONB column — Hibernate 6/7 {@code @JdbcTypeCode(SqlTypes.JSON)} serializes via Jackson. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attributes", columnDefinition = "jsonb", nullable = false)
  private Map<String, String> attributes;

  @Column(name = "price_cents", nullable = false)
  private Long priceCents;

  @Column(nullable = false, length = 3)
  private String currency;

  /**
   * Computes the deterministic SKU for a variant of a product. The canonical form sorts attribute
   * keys lexicographically and joins {@code key=value} pairs with {@code |}; the result is hashed
   * with SHA-256 (JDK stdlib, no Guava), hex-encoded, truncated to 16 chars, and prefixed with
   * the product slug — e.g. {@code "red-shirt" + "-" + "3f2a91c0e8b74d11"}.
   *
   * <p>Stability: same inputs always produce the same string byte-for-byte (AC #3).
   */
  public static String computeSku(String productSlug, Map<String, String> attributes) {
    String canonical =
        attributes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining("|"));
    try {
      String hashHex =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)))
              .substring(0, 16);
      return productSlug + "-" + hashHex;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandated by the JRE", e);
    }
  }
}
