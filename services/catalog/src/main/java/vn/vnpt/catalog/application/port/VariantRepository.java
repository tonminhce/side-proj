package vn.vnpt.catalog.application.port;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.catalog.domain.Variant;

/**
 * Spring Data JPA repository (port) for {@link Variant} aggregates (Story 1.2 / 1.3).
 * Lives in {@code application.port} — see {@link ProductRepository} for the layering
 * rationale.
 */
public interface VariantRepository extends JpaRepository<Variant, Long> {
  List<Variant> findByProductUuid(Long productUuid);

  Optional<Variant> findBySku(String sku);
}
