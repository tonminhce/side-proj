package vn.vnpt.catalog.application.query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-side projection of {@link vn.vnpt.catalog.domain.Product} for the admin catalog list
 * (Story 1.4 / FR-6).
 *
 * <p>Lives in {@code application.query} (NOT {@code domain}) because it's a projection used only
 * by the read endpoint — domain stays aggregate-pure. See {@code
 * CatalogPackageBoundaryTest.web_doesNotLeakQueryDtosIntoDomain}.
 */
public record ProductSummary(
    Long productUuid,
    String sku,
    String name,
    String brand,
    String description,
    LocalDateTime createdAt,
    List<VariantSummary> variants) {}