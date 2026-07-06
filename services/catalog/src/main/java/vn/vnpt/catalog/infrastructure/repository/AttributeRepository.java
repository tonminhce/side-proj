package vn.vnpt.catalog.infrastructure.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.catalog.domain.Attribute;

/** Spring Data JPA repository for {@link Attribute} definitions (Story 1.2). Derived queries only. */
public interface AttributeRepository extends JpaRepository<Attribute, Long> {
  List<Attribute> findByProductUuidOrderBySortOrderAsc(Long productUuid);
}
