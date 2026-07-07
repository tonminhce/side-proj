package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@link ShipInventoryUseCase} — Story 1.8 / FR-11 (SHIPPED).
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ShipInventoryUseCaseTest {

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

  @Autowired ShipInventoryUseCase shipUseCase;
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
                    .code("HCM-01-SHIP-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .region(Region.SOUTH).build())
            .getUuid();
  }

  @Test
  void ship_deductsFromAvailable_andEmitsShipped() {
    // Seed 10 units.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(100L)
            .warehouseId(warehouseId)
            .delta(10L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    InventoryLedgerEntry entry =
        shipUseCase.ship(
            new ShipInventoryCommand(100L, warehouseId, 3L, "ship-step-" + System.nanoTime()));

    assertThat(entry.getDelta()).isEqualTo(-3L);
    assertThat(entry.getReason()).isEqualTo("ship");

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer shipLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'ship'", Integer.class);
    assertThat(shipLedgerCount).isEqualTo(1);

    Integer lifecycleOutboxCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.lifecycle'"
                + " AND aggregate_id = ?",
            Integer.class,
            entry.getUuid());
    assertThat(lifecycleOutboxCount).isEqualTo(1);
  }

  @Test
  void ship_onInsufficientStock_throwsInsufficientStockException() {
    // No seed ledger → available is 0.
    assertThatThrownBy(
            () ->
                shipUseCase.ship(
                    new ShipInventoryCommand(
                        200L, warehouseId, 5L, "ship-step-insuf-" + System.nanoTime())))
        .isInstanceOf(InsufficientStockException.class);
  }

  @Test
  void ship_onUnknownWarehouse_throwsWarehouseNotFoundException() {
    assertThatThrownBy(
            () ->
                shipUseCase.ship(
                    new ShipInventoryCommand(
                        300L, 999_999L, 1L, "ship-step-wh-" + System.nanoTime())))
        .isInstanceOf(WarehouseNotFoundException.class);
  }

  /**
   * QA-pass gap — use-case-level validation. quantity <= 0 → IllegalArgumentException with
   * the field name in the message. The controller test covers the HTTP boundary; the
   * use-case guard is the first line of defense and was not directly pinned.
   */
  @Test
  void ship_throwsIllegalArgumentWhenQuantityIsZero() {
    assertThatThrownBy(
            () ->
                shipUseCase.ship(
                    new ShipInventoryCommand(
                        400L, warehouseId, 0L, "ship-step-q0-" + System.nanoTime())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  /** QA-pass gap — same as {@link #ship_throwsIllegalArgumentWhenQuantityIsZero()} but negative. */
  @Test
  void ship_throwsIllegalArgumentWhenQuantityIsNegative() {
    assertThatThrownBy(
            () ->
                shipUseCase.ship(
                    new ShipInventoryCommand(
                        401L, warehouseId, -1L, "ship-step-qneg-" + System.nanoTime())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  /** QA-pass gap — blank sagaStepId → IllegalArgumentException. */
  @Test
  void ship_throwsIllegalArgumentWhenSagaStepIdIsBlank() {
    assertThatThrownBy(
            () -> shipUseCase.ship(new ShipInventoryCommand(402L, warehouseId, 1L, "  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sagaStepId");
  }

  /**
   * QA-pass gap — partial availability (available=2, requesting 5) must throw
   * {@link InsufficientStockException} carrying the actual available value. Existing
   * {@code ship_onInsufficientStock_throwsInsufficientStockException} only exercises the
   * 0-available case; a regression that swapped the available/requested in the exception
   * payload would not be caught there.
   */
  @Test
  void ship_onPartialAvailable_throwsInsufficientStockWithActualAvailable() {
    // Seed on_hand = 2.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(500L)
            .warehouseId(warehouseId)
            .delta(2L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
    assertThatThrownBy(
            () ->
                shipUseCase.ship(
                    new ShipInventoryCommand(
                        500L, warehouseId, 5L, "ship-step-partial-" + System.nanoTime())))
        .isInstanceOf(InsufficientStockException.class)
        .satisfies(
            e -> {
              InsufficientStockException ex = (InsufficientStockException) e;
              assertThat(ex.getRequested()).isEqualTo(5L);
              assertThat(ex.getAvailable()).isEqualTo(2L);
              assertThat(ex.getVariantId()).isEqualTo(500L);
              assertThat(ex.getWarehouseId()).isEqualTo(warehouseId);
            });
  }
}