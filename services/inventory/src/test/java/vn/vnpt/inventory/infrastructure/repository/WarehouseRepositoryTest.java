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
import vn.vnpt.inventory.domain.Warehouse;

/**
 * Integration test for {@link WarehouseRepository} — Spring context with Testcontainers Postgres.
 * Pins {@code findByCode} lookup and {@code findByIsActiveTrueAndIsDeletedFalse} (soft-delete
 * exclusion).
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
    repository.save(Warehouse.builder().code(code).displayName("Ho Chi Minh").build());

    Warehouse found = repository.findByCode(code).orElseThrow();
    assertThat(found.getCode()).isEqualTo(code);
    assertThat(found.getDisplayName()).isEqualTo("Ho Chi Minh");
  }

  @Test
  void findByIsActiveTrueAndIsDeletedFalse_excludesSoftDeleted() {
    String activeCode = "HCM-ACTIVE-" + System.nanoTime();
    String deletedCode = "HN-DELETED-" + System.nanoTime();
    Warehouse active = repository.save(Warehouse.builder().code(activeCode).displayName("Ho Chi Minh").build());
    Warehouse deleted =
        repository.save(Warehouse.builder().code(deletedCode).displayName("Ha Noi").build());
    deleted.setIsActive(false);
    deleted.setIsDeleted(true);
    repository.save(deleted);

    List<Warehouse> rows = repository.findByIsActiveTrueAndIsDeletedFalse();
    assertThat(rows).extracting(Warehouse::getUuid).contains(active.getUuid()).doesNotContain(deleted.getUuid());
  }
}