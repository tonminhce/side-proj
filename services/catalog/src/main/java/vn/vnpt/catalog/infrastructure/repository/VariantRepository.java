package vn.vnpt.catalog.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.catalog.domain.Variant;

/** Spring Data JPA repository for {@link Variant} aggregates (Story 1.2). Derived queries only. */
public interface VariantRepository extends JpaRepository<Variant, Long> {
  List<Variant> findByProductUuid(Long productUuid);

  Optional<Variant> findBySku(String sku);
}
