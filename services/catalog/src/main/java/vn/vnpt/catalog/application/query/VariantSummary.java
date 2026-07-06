package vn.vnpt.catalog.application.query;

import java.util.Map;

/** Read-side projection of {@link vn.vnpt.catalog.domain.Variant} (Story 1.4 / FR-6). */
public record VariantSummary(
    Long variantUuid,
    String sku,
    Map<String, String> attributes,
    long priceCents,
    String currency) {}