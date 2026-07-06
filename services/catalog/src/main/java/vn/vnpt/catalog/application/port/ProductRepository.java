package vn.vnpt.catalog.application.port;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.vnpt.catalog.domain.Product;

/**
 * Spring Data JPA repository (port) for {@link Product} aggregates (Story 1.2 / 1.3 / 1.4).
 *
 * <p>Lives in {@code application.port} so the application layer can inject it without
 * depending on {@code infrastructure}. Spring Data JPA still generates the implementation
 * because the package scan in {@code CatalogApplication} covers {@code vn.vnpt.catalog}.
 *
 * <p>{@link #findByTenantId(String, Pageable)} (Story 1.4) uses {@code JOIN FETCH p.variants}
 * to load parent + children in one query — the variants relationship is {@code
 * FetchType.LAZY} so a plain derived query would N+1 across {@code size=20} rows. {@code
 * DISTINCT} dedupes Hibernate's join-row duplication.
 */
public interface ProductRepository extends JpaRepository<Product, Long> {
  Optional<Product> findBySku(String sku);

  List<Product> findByIsActiveTrueAndIsDeletedFalse();

  @Query(
      "SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.variants"
          + " WHERE p.tenantId = :tenantId ORDER BY p.createdAt DESC")
  Page<Product> findByTenantId(@Param("tenantId") String tenantId, Pageable pageable);
}
