package vn.vnpt.catalog.application;

import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.catalog.application.port.OutboxPublisher;
import vn.vnpt.catalog.domain.Variant;
import vn.vnpt.catalog.domain.event.CatalogProductPriceChanged;
import vn.vnpt.catalog.domain.exception.VariantNotFoundException;
import vn.vnpt.catalog.application.port.VariantRepository;

/**
 * Application use case: update a {@link Variant}'s price (Story 1.3 / AC #11).
 *
 * <p>Captures the OLD price before mutation so the {@code catalog.product.price_changed}
 * event payload carries both old and new values. Downstream consumers (Search, Notification,
 * Pricing) need the diff for "price dropped from X to Y" UI. A bug that captures the old
 * price AFTER mutation would store the new price in both fields — the regression test in
 * {@code UpdatePriceUseCaseTest#updatePrice_capturesOldPriceBeforeMutation} pins the order.
 */
@Service
@RequiredArgsConstructor
public class UpdatePriceUseCase {

  private final VariantRepository variants;
  private final OutboxPublisher outbox;

  @Transactional
  public Variant updatePrice(UpdatePriceCommand cmd) {
    if (cmd.newPriceCents() < 0) {
      throw new IllegalArgumentException("price must be non-negative");
    }
    Variant variant =
        variants.findById(cmd.variantUuid())
            .orElseThrow(() -> new VariantNotFoundException(cmd.variantUuid()));

    // ponytail: capture BEFORE mutation
    long oldPriceCents = variant.getPriceCents();
    variant.setPriceCents(cmd.newPriceCents());
    variants.save(variant);

    CatalogProductPriceChanged event =
        CatalogProductPriceChanged.newBuilder()
            .setVariantUuid(variant.getUuid())
            .setProductUuid(variant.getProductUuid())
            .setOldPriceCents(oldPriceCents)
            .setNewPriceCents(cmd.newPriceCents())
            .setCurrency(variant.getCurrency())
            .setOccurredAt(Instant.now().toString())
            .build();
    outbox.append(
        "Variant", variant.getUuid(), "catalog.product.price_changed", event, Map.of());

    return variant;
  }
}
