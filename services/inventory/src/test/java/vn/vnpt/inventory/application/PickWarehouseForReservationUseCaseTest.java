package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@link PickWarehouseForReservationUseCase} — Story 1.7 / FR-10.
 *
 * <p>Pins three behaviors: (a) in-region first match wins; (b) cross-region fallback when
 * in-region is empty; (c) empty Optional when no warehouse has enough stock.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class PickWarehouseForReservationUseCaseTest {

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

  @Autowired PickWarehouseForReservationUseCase picker;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired InventoryLedgerEntryRepository ledgerRepository;
  @Autowired DataSource dataSource;

  @BeforeEach
  void clean() {
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void pickWarehouseId_picksInRegionWarehouseWithEnoughStock() {
    Long hcm = saveWarehouse("HCM-PICK-1-" + System.nanoTime(), Region.SOUTH);
    Long hn = saveWarehouse("HN-PICK-1-" + System.nanoTime(), Region.NORTH);

    seedOnHand(100L, hcm, 10L);
    seedOnHand(100L, hn, 10L);

    Long picked = picker.pickWarehouseId(100L, Region.SOUTH, 7L).orElseThrow();
    assertThat(picked).isEqualTo(hcm);
  }

  @Test
  void pickWarehouseId_fallsBackToCrossRegionWhenInRegionEmpty() {
    Long hcm = saveWarehouse("HCM-PICK-2-" + System.nanoTime(), Region.SOUTH);
    Long hn = saveWarehouse("HN-PICK-2-" + System.nanoTime(), Region.NORTH);

    seedOnHand(100L, hn, 10L);
    // HCM has zero stock — picker must fall back to HN.

    Long picked = picker.pickWarehouseId(100L, Region.SOUTH, 5L).orElseThrow();
    assertThat(picked).isEqualTo(hn);
  }

  @Test
  void pickWarehouseId_returnsEmptyWhenNoWarehouseHasEnough() {
    Long hcm = saveWarehouse("HCM-PICK-3-" + System.nanoTime(), Region.SOUTH);
    Long hn = saveWarehouse("HN-PICK-3-" + System.nanoTime(), Region.NORTH);

    seedOnHand(100L, hcm, 1L);
    seedOnHand(100L, hn, 2L);

    var result = picker.pickWarehouseId(100L, Region.SOUTH, 50L);
    assertThat(result).isEmpty();
  }

  /**
   * Gap coverage: when NO warehouse exists in the requested region at all, the picker falls
   * through to the cross-region list. Different code path from {@link
   * #pickWarehouseId_fallsBackToCrossRegionWhenInRegionEmpty} (which seeds an in-region
   * warehouse with insufficient stock). Both paths must end at the same cross-region pick.
   */
  @Test
  void pickWarehouseId_fallsBackToCrossRegionWhenInRegionListIsEmpty() {
    // Only HN exists; request from SOUTH (no SOUTH warehouses at all).
    Long hn = saveWarehouse("HN-PICK-4-" + System.nanoTime(), Region.NORTH);
    seedOnHand(100L, hn, 10L);

    Long picked = picker.pickWarehouseId(100L, Region.SOUTH, 5L).orElseThrow();
    assertThat(picked).isEqualTo(hn);
  }

  /** Gap coverage: empty Optional when no warehouse exists anywhere. */
  @Test
  void pickWarehouseId_returnsEmptyWhenNoWarehousesExist() {
    var result = picker.pickWarehouseId(100L, Region.SOUTH, 1L);
    assertThat(result).isEmpty();
  }

  private Long saveWarehouse(String code, Region region) {
    return warehouseRepository
        .save(Warehouse.builder().code(code).displayName(code).region(region).build())
        .getUuid();
  }

  private void seedOnHand(long variantId, long warehouseId, long qty) {
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(variantId)
            .warehouseId(warehouseId)
            .delta(qty)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
  }
}