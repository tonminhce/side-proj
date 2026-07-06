package vn.vnpt.catalog.application;

import java.util.List;
import java.util.Map;

/**
 * Application command for {@link CreateProductUseCase} (Story 1.2 / FR-1, FR-2, FR-4).
 *
 * <p>Pure data record — validation lives in the use case.
 *
 * @param name product name (required)
 * @param sku product-level SKU slug (required, unique — DB constraint)
 * @param description optional product description
 * @param brand optional brand string
 * @param variants list of variant specs (may be empty)
 * @param attributes list of attribute definitions (may be empty)
 */
public record CreateProductCommand(
    String name,
    String sku,
    String description,
    String brand,
    List<VariantSpec> variants,
    List<AttributeSpec> attributes) {

  /** Per-variant selection. {@code attributes} keys are matched against the product's {@code
   * attributes} by canonical name (e.g. {@code "color"} / {@code "size"}). */
  public record VariantSpec(Map<String, String> attributes, long priceCents, String currency) {}

  /** Attribute definition (canonical key + human display label + sort order). */
  public record AttributeSpec(String name, String displayName, int sortOrder) {}
}
