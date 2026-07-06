package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.vnpt.inventory.application.query.OnHandView;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;

/**
 * Integration test for {@link OnHandUseCase} — full Spring context with Testcontainers Postgres.
 * Pins the sum-derivation correctness: 3 ledger entries with deltas {@code +5, -2, +10} yield
 * {@code on_hand = 13}.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class OnHandUseCaseTest {

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

  @Autowired OnHandUseCase useCase;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired InventoryLedgerEntryRepository ledgerRepository;
  @Autowired DataSource dataSource;

  @org.junit.jupiter.api.BeforeEach
  void clean() {
    new JdbcTemplate(dataSource).execute("TRUNCATE TABLE inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void findOnHand_returnsAggregatedView() {
    long warehouseId =
        warehouseRepository.save(
                Warehouse.builder().code("HCM-01-UC-" + System.nanoTime()).displayName("Ho Chi Minh").build())
            .getUuid();

    ledgerRepository.save(entry(100L, warehouseId, 5L, 1L));
    ledgerRepository.save(entry(100L, warehouseId, -2L, 2L));
    ledgerRepository.save(entry(100L, warehouseId, 10L, 3L));

    var rows = useCase.findOnHand(100L);
    assertThat(rows).hasSize(1);
    OnHandView view = rows.get(0);
    assertThat(view.variantId()).isEqualTo(100L);
    assertThat(view.onHand()).isEqualTo(13L);
    assertThat(view.entryCount()).isEqualTo(3L);
  }

  /**
   * AC #13 — Story 1.7 multi-warehouse breaks the per-warehouse query path. Even in v1
   * single-warehouse default, the method exists and returns the same projection shape as
   * the variant-wide query, scoped to one (variant, warehouse) pair.
   */
  @Test
  void findOnHandForWarehouse_returnsAggregatedView() {
    long warehouseA =
        warehouseRepository
            .save(
                Warehouse.builder()
                    .code("HCM-A-" + System.nanoTime())
                    .displayName("Ho Chi Minh A")
                    .build())
            .getUuid();
    long warehouseB =
        warehouseRepository
            .save(
                Warehouse.builder()
                    .code("HN-B-" + System.nanoTime())
                    .displayName("Ha Noi B")
                    .build())
            .getUuid();

    ledgerRepository.save(entry(100L, warehouseA, 5L, 1L));
    ledgerRepository.save(entry(100L, warehouseA, -2L, 2L));
    ledgerRepository.save(entry(100L, warehouseB, 7L, 3L)); // separate warehouse; must NOT sum

    var rows = useCase.findOnHandForWarehouse(100L, warehouseA);
    assertThat(rows).hasSize(1);
    OnHandView view = rows.get(0);
    assertThat(view.variantId()).isEqualTo(100L);
    assertThat(view.warehouseId()).isEqualTo(warehouseA);
    assertThat(view.onHand()).isEqualTo(3L);
    assertThat(view.entryCount()).isEqualTo(2L);
  }

  private static InventoryLedgerEntry entry(long variantId, long warehouseId, long delta, long eventId) {
    return InventoryLedgerEntry.builder()
        .variantId(variantId)
        .warehouseId(warehouseId)
        .delta(delta)
        .reason("receive")
        .eventId(eventId)
        .tenantId("default")
        .build();
  }
}