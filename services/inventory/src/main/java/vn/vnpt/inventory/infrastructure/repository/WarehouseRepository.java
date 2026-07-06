package vn.vnpt.inventory.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.inventory.domain.Warehouse;

/**
 * Spring Data JPA repository for {@link Warehouse}. Standard CRUD plus the two derived queries
 * the use cases need: lookup by admin-managed {@code code} slug, and the active+non-deleted
 * list for boot-time singleton resolution.
 */
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

  Optional<Warehouse> findByCode(String code);

  /** Used by {@code CatalogEventListener} to seed/lookup the v1 single-warehouse default. */
  List<Warehouse> findByIsActiveTrueAndIsDeletedFalse();
}