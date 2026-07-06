package vn.vnpt.catalog.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.catalog.domain.Product;

/**
 * Spring Data JPA repository for {@link Product} aggregates (Story 1.2).
 *
 * <p>Derived queries only — Spring Data translates the method name to SQL.
 * For {@code findByIsActiveTrueAndIsDeletedFalse}, the engine emits {@code WHERE is_active = true
 * AND is_deleted = false}; Postgres boolean {@code true}/{@code false} literals match.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
  Optional<Product> findBySku(String sku);

  List<Product> findByIsActiveTrueAndIsDeletedFalse();
}
