package vn.vnpt.catalog.application.port;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.catalog.domain.Product;

/**
 * Spring Data JPA repository (port) for {@link Product} aggregates (Story 1.2 / 1.3).
 *
 * <p>Lives in {@code application.port} so the application layer can inject it without
 * depending on {@code infrastructure}. Spring Data JPA still generates the implementation
 * because the package scan in {@code CatalogApplication} covers {@code vn.vnpt.catalog}.
 * Derived queries only — Spring Data translates the method name to SQL.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
  Optional<Product> findBySku(String sku);

  List<Product> findByIsActiveTrueAndIsDeletedFalse();
}
