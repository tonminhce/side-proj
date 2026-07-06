package vn.vnpt.catalog.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.catalog.application.port.ProductRepository;
import vn.vnpt.catalog.application.query.ProductSummary;
import vn.vnpt.catalog.application.query.VariantSummary;
import vn.vnpt.catalog.domain.Product;

/**
 * Read-side use case: list products (with inlined variants) for the admin catalog view
 * (Story 1.4 / FR-6).
 *
 * <p>Reads DIRECTLY from Postgres (NOT Elasticsearch) — see architecture ADR-03 + FR-6. ES
 * bootstrap is Story 6.1; admin reads tolerate canonical-store latency because they're
 * low-volume and staff-internal. The endpoint becomes {@code SearchService.findByQuery("*")}
 * with a Postgres-fallback annotation when Story 6.1 lands.
 *
 * <p>FR-7's {@code audit_trail} table does NOT apply here: {@code audit_trail} logs MUTATIONS,
 * not reads. The read view is by design unaudited (Story 8.1 owns the writes + audit_trail
 * table).
 *
 * <p>{@code @Transactional(readOnly = true)} is the canonical Hibernate read-path perf lever —
 * skips the dirty-checking snapshot.
 */
@Service
@RequiredArgsConstructor
public class ListProductsUseCase {

  private final ProductRepository products;

  @Transactional(readOnly = true)
  public Page<ProductSummary> execute(String tenantId, Pageable pageable) {
    if (pageable.getPageSize() > 100) {
      throw new IllegalArgumentException("size must be <= 100");
    }
    return products.findByTenantId(tenantId, pageable).map(this::toSummary);
  }

  // ponytail: paginated map, single-pass; no caching layer — Story 6.x with ES handles that.
  private ProductSummary toSummary(Product p) {
    List<VariantSummary> variants =
        p.getVariants().stream()
            .map(
                v ->
                    new VariantSummary(
                        v.getUuid(),
                        v.getSku(),
                        v.getAttributes(),
                        v.getPriceCents() == null ? 0L : v.getPriceCents(),
                        v.getCurrency()))
            .toList();
    return new ProductSummary(
        p.getUuid(),
        p.getSku(),
        p.getName(),
        p.getBrand(),
        p.getDescription(),
        p.getCreatedAt(),
        variants);
  }
}