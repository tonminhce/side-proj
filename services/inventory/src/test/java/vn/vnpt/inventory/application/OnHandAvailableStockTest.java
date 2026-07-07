package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import vn.vnpt.inventory.application.query.AvailableStockView;
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@code OnHandUseCase.findAvailable} — Story 1.6 / FR-9.
 *
 * <p>Pins the available-stock computation: {@code available = onHand - active_reservations}.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class OnHandAvailableStockTest {

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

  @Autowired OnHandUseCase onHandUseCase;
  @Autowired ReserveInventoryUseCase reserveUseCase;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired InventoryLedgerEntryRepository ledgerRepository;
  @Autowired DataSource dataSource;

  private Long warehouseId;

  @BeforeEach
  void seedWarehouse() {
    new JdbcTemplate(dataSource)
        .execute(
            "TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
    warehouseId =
        warehouseRepository
            .save(
                Warehouse.builder()
                    .code("HCM-01-AV-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .region(Region.SOUTH).build())
            .getUuid();
  }

  @Test
  void findAvailable_returnsOnHandMinusActiveReservations() {
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(950L)
            .warehouseId(warehouseId)
            .delta(10L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    // No reservations yet → available = 10.
    AvailableStockView before = onHandUseCase.findAvailable(950L, warehouseId).orElseThrow();
    assertThat(before.onHand()).isEqualTo(10L);
    assertThat(before.activeReservations()).isEqualTo(0L);
    assertThat(before.available()).isEqualTo(10L);

    // Reserve 3 → ledger gets +10 and -3 (the reservation's decrement row); available = 7.
    // The ledger already reflects the reservation, so no double-counting.
    reserveUseCase.reserve(
        new ReserveInventoryCommand(
            950L, warehouseId, null, 3L, "step-av-1-" + System.nanoTime(), null, Duration.ofMinutes(15)));

    AvailableStockView after = onHandUseCase.findAvailable(950L, warehouseId).orElseThrow();
    assertThat(after.onHand()).isEqualTo(7L);
    assertThat(after.activeReservations()).isEqualTo(3L);
    assertThat(after.available()).isEqualTo(7L);
  }
}