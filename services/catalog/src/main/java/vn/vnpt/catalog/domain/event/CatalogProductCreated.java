package vn.vnpt.catalog.domain.event;

import java.time.Instant;

/**
 * Domain event emitted when a {@code Product} aggregate is created (Story 1.2 / FR-1, FR-2, FR-4).
 *
 * <p>Story 1.3 (Avro strict compat, FR-5) replaces this hand-written record with an
 * Avro-generated type at the same fully-qualified name ({@code
 * vn.vnpt.catalog.domain.event.CatalogProductCreated}); the {@code createProduct} use case
 * signature does NOT change.
 *
 * @param productUuid Snowflake ID of the new product (matches {@code Product.uuid})
 * @param sku the product-level SKU slug (NOT a variant SKU)
 * @param occurredAt timestamp the event was emitted (use case set to {@link Instant#now()})
 */
public record CatalogProductCreated(Long productUuid, String sku, Instant occurredAt) {}
