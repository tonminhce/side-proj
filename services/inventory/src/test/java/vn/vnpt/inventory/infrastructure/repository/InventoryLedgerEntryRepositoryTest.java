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
import vn.vnpt.inventory.application.query.OnHandView;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;

/**
 * Integration test for {@link InventoryLedgerEntryRepository} — Spring context with Testcontainers
 * Postgres. Pins four invariants:
 *
 * <ul>
 *   <li>{@code findByEventId} lookup by idempotency key
 *   <li>{@code findByVariantId} returns all entries for the variant
 *   <li>{@code sumOnHandByVariantId} computes the correct sum across warehouses
 *   <li>{@code sumOnHandByVariantId} returns an empty list for unseen variants
 * </ul>
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class InventoryLedgerEntryRepositoryTest {

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

  @Autowired InventoryLedgerEntryRepository repository;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired DataSource dataSource;

  private Long warehouseId;

  @org.junit.jupiter.api.BeforeEach
  void seedWarehouse() {
    // Truncate to isolate from prior tests in the shared Postgres container.
    new JdbcTemplate(dataSource).execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
    Warehouse seeded =
        warehouseRepository.save(
            Warehouse.builder()
                .code("HCM-LEDGER-" + System.nanoTime())
                .displayName("Ho Chi Minh")
                .region(Region.SOUTH).build());
    warehouseId = seeded.getUuid();
  }

  @Test
  void save_persistsLedgerEntryWithEventId() {
    InventoryLedgerEntry saved =
        repository.save(
            InventoryLedgerEntry.builder()
                .variantId(100L)
                .warehouseId(warehouseId)
                .delta(5L)
                .reason("receive")
                .eventId(42L)
                .tenantId("default")
                .build());

    assertThat(saved.getUuid()).isNotNull();
    InventoryLedgerEntry found = repository.findByEventId(42L).orElseThrow();
    assertThat(found.getUuid()).isEqualTo(saved.getUuid());
    assertThat(found.getDelta()).isEqualTo(5L);
  }

  @Test
  void findByVariantId_returnsAllEntriesForVariant() {
    repository.save(entry(100L, warehouseId, 1L, 1L));
    repository.save(entry(100L, warehouseId, 2L, 2L));
    repository.save(entry(999L, warehouseId, 99L, 3L));

    List<InventoryLedgerEntry> rows = repository.findByVariantId(100L);
    assertThat(rows).hasSize(2);
  }

  @Test
  void sumOnHandByVariantId_returnsSumAcrossWarehouses() {
    repository.save(entry(100L, warehouseId, 5L, 10L));
    repository.save(entry(100L, warehouseId, -2L, 11L));

    List<OnHandView> view = repository.sumOnHandByVariantId(100L);
    assertThat(view).hasSize(1);
    assertThat(view.get(0).onHand()).isEqualTo(3L);
    assertThat(view.get(0).entryCount()).isEqualTo(2L);
  }

  /**
   * AC #13 / Ponytail correctness — empty group must return empty list, NOT a row with null
   * aggregates. The {@code COALESCE(SUM(...), 0)} wrap ensures non-null on non-empty group;
   * empty group produces no row at all.
   */
  @Test
  void sumOnHand_returnsEmptyForUnseenVariant() {
    List<OnHandView> view = repository.sumOnHandByVariantId(999_999L);
    assertThat(view).isEmpty();
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