package vn.vnpt.inventory.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.inventory.InventoryApplication;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;

/**
 * Integration test for {@link WarehouseRepository} — Spring context with Testcontainers Postgres.
 * Pins {@code findByCode} lookup and {@code findByIsActiveTrueAndIsDeletedFalse} (soft-delete
 * exclusion). Extended in Story 1.7 with {@code findByRegionAndIsActiveTrueAndIsDeletedFalse}
 * for FR-10 picker dispatch.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class WarehouseRepositoryTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("inventory_db")
          .withUsername("inventory_user")
          .withPassword("inventory_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired WarehouseRepository repository;
  @Autowired DataSource dataSource;

  @org.junit.jupiter.api.BeforeEach
  void clean() {
    new JdbcTemplate(dataSource).execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void findByCode_returnsWarehouse() {
    String code = "HCM-WAREHOUSE-" + System.nanoTime();
    repository.save(Warehouse.builder().code(code).displayName("Ho Chi Minh").region(Region.SOUTH).build());

    Warehouse found = repository.findByCode(code).orElseThrow();
    assertThat(found.getCode()).isEqualTo(code);
    assertThat(found.getDisplayName()).isEqualTo("Ho Chi Minh");
    assertThat(found.getRegion()).isEqualTo(Region.SOUTH);
  }

  @Test
  void findByIsActiveTrueAndIsDeletedFalse_excludesSoftDeleted() {
    String activeCode = "HCM-ACTIVE-" + System.nanoTime();
    String deletedCode = "HN-DELETED-" + System.nanoTime();
    Warehouse active = repository.save(Warehouse.builder().code(activeCode).displayName("Ho Chi Minh").region(Region.SOUTH).build());
    Warehouse deleted =
        repository.save(Warehouse.builder().code(deletedCode).displayName("Ha Noi").region(Region.NORTH).build());
    deleted.setIsActive(false);
    deleted.setIsDeleted(true);
    repository.save(deleted);

    List<Warehouse> rows = repository.findByIsActiveTrueAndIsDeletedFalse();
    assertThat(rows).extracting(Warehouse::getUuid).contains(active.getUuid()).doesNotContain(deleted.getUuid());
  }

  /** Story 1.7 / FR-10 — region-scoped active list used by {@code PickWarehouseForReservationUseCase}. */
  @Test
  void findByRegionAndIsActiveTrueAndIsDeletedFalse_returnsOnlyActiveInRegion() {
    Warehouse hcmActive = repository.save(
        Warehouse.builder().code("HCM-RG-A-" + System.nanoTime()).displayName("HCM").region(Region.SOUTH).build());
    Warehouse hcmDeleted = repository.save(
        Warehouse.builder().code("HCM-RG-D-" + System.nanoTime()).displayName("HCM-D").region(Region.SOUTH).build());
    hcmDeleted.setIsDeleted(true);
    repository.save(hcmDeleted);
    Warehouse hnActive = repository.save(
        Warehouse.builder().code("HN-RG-A-" + System.nanoTime()).displayName("HN").region(Region.NORTH).build());

    List<Warehouse> southActive = repository.findByRegionAndIsActiveTrueAndIsDeletedFalse(Region.SOUTH);
    assertThat(southActive).extracting(Warehouse::getUuid).contains(hcmActive.getUuid()).doesNotContain(hcmDeleted.getUuid());

    List<Warehouse> northActive = repository.findByRegionAndIsActiveTrueAndIsDeletedFalse(Region.NORTH);
    assertThat(northActive).extracting(Warehouse::getUuid).containsExactly(hnActive.getUuid());
  }
}