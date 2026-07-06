package vn.vnpt.catalog.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.catalog.application.port.OutboxPublisher;
import vn.vnpt.catalog.domain.Attribute;
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.Variant;
import vn.vnpt.catalog.domain.event.CatalogProductCreated;
import vn.vnpt.catalog.application.port.AttributeRepository;
import vn.vnpt.catalog.application.port.ProductRepository;
import vn.vnpt.catalog.application.port.VariantRepository;

/**
 * Application use case: create a {@link Product} aggregate with its first set of variants and
 * attribute definitions, and emit a {@link CatalogProductCreated} outbox event in the same
 * transaction (ADR-04 atomicity).
 *
 * <p>Story 1.2 public entry point for the catalog API (ADR-01 / ADR-14). Cross-module callers (the
 * admin BFF in Story 1.4 / 8.x) go through this interface — entities and repositories are
 * package-private from outside the catalog module (architecture.md line 879).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateProductUseCase {

  private final ProductRepository products;
  private final VariantRepository variants;
  private final AttributeRepository attributes;
  private final OutboxPublisher outbox;

  @Transactional
  public Product create(CreateProductCommand cmd) {
    if (cmd.name() == null || cmd.name().isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (cmd.sku() == null || cmd.sku().isBlank()) {
      throw new IllegalArgumentException("sku is required");
    }

    Product product =
        products.save(Product.create(cmd.name(), cmd.sku(), cmd.description(), cmd.brand()));

    List<Variant> persistedVariants =
        cmd.variants().stream()
            .map(
                spec ->
                    variants.save(
                        Variant.builder()
                            .productUuid(product.getUuid())
                            .sku(Variant.computeSku(product.getSku(), spec.attributes()))
                            .attributes(spec.attributes())
                            .priceCents(spec.priceCents())
                            .currency(spec.currency() == null ? "VND" : spec.currency())
                            .build()))
            .toList();

    List<Attribute> persistedAttributes =
        cmd.attributes().stream()
            .map(
                spec ->
                    attributes.save(
                        Attribute.builder()
                            .productUuid(product.getUuid())
                            .name(spec.name())
                            .displayName(spec.displayName())
                            .sortOrder(spec.sortOrder())
                            .build()))
            .toList();

    outbox.append(
        "Product",
        product.getUuid(),
        "catalog.product.created",
        CatalogProductCreated.newBuilder()
            .setProductUuid(product.getUuid())
            .setSku(product.getSku())
            .setName(product.getName())
            .setOccurredAt(Instant.now().toString())
            .build(),
        Map.of());

    if (log.isDebugEnabled()) {
      log.debug(
          "Created product uuid={} sku={} variants={} attributes={}",
          product.getUuid(),
          product.getSku(),
          persistedVariants.size(),
          persistedAttributes.size());
    }

    return product;
  }
}
