package vn.vnpt.catalog.application.port;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.catalog.domain.Attribute;

/**
 * Spring Data JPA repository (port) for {@link Attribute} definitions (Story 1.2 / 1.3).
 * Lives in {@code application.port} — see {@link ProductRepository} for the layering
 * rationale.
 */
public interface AttributeRepository extends JpaRepository<Attribute, Long> {
  List<Attribute> findByProductUuidOrderBySortOrderAsc(Long productUuid);
}
