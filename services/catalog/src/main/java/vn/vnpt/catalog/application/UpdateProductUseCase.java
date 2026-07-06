package vn.vnpt.catalog.application;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.catalog.application.port.OutboxPublisher;
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.event.CatalogProductUpdated;
import vn.vnpt.catalog.domain.exception.ProductNotFoundException;
import vn.vnpt.catalog.application.port.ProductRepository;

/**
 * Application use case: update a {@link Product} aggregate's mutable fields
 * (Story 1.3 / AC #10).
 *
 * <p>Scope: {@code name}, {@code description}, {@code brand} only. The {@code sku} is
 * immutable post-creation (sku is the admin-managed slug; variant-level SKUs are hashes
 * computed by {@code Variant.computeSku}). Variant- and attribute-level updates land in
 * a later story.
 *
 * <p>Atomicity: the JPA save + outbox append run in the same transaction (the implicit
 * {@code @Transactional} and the JPA / JdbcTemplate sharing the same DataSource per
 * ADR-04). On rollback both the product update and the event are rolled back.
 */
@Service
@RequiredArgsConstructor
public class UpdateProductUseCase {

  private final ProductRepository products;
  private final OutboxPublisher outbox;

  @Transactional
  public Product update(UpdateProductCommand cmd) {
    if (cmd.name() == null || cmd.name().isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    Product product =
        products.findById(cmd.productUuid())
            .orElseThrow(() -> new ProductNotFoundException(cmd.productUuid()));

    product.setName(cmd.name());
    product.setDescription(cmd.description());
    product.setBrand(cmd.brand());
    products.save(product);

    CatalogProductUpdated event =
        CatalogProductUpdated.newBuilder()
            .setProductUuid(product.getUuid())
            .setSku(product.getSku())
            .setName(product.getName())
            .setOccurredAt(Instant.now().toString())
            .build();
    outbox.append(
        "Product", product.getUuid(), "catalog.product.updated", event, Map.of());

    return product;
  }
}
