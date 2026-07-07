package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@link ReservationSweeperJob} — Story 1.6 / FR-9 TTL auto-expiry.
 *
 * <p>Seeds 3 ACTIVE reservations with {@code expires_at = now() - 1m}; invokes
 * {@code sweepExpired()} directly (not via {@code @Scheduled} cron); asserts all 3 are
 * RELEASED + 3 outbox rows for {@code inventory.released}.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ReservationSweeperJobTest {

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

  @Autowired ReserveInventoryUseCase reserveUseCase;
  @Autowired ReservationSweeperJob sweeper;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired InventoryLedgerEntryRepository ledgerRepository;
  @Autowired InventoryReservationRepository reservationRepository;
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
                    .code("HCM-01-SWEEP-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .region(Region.SOUTH).build())
            .getUuid();
  }

  @Test
  void sweepExpired_releasesAllExpiredActiveReservations() {
    // Seed ledger + reserve 3 ACTIVE reservations.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(700L)
            .warehouseId(warehouseId)
            .delta(10L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    for (int i = 0; i < 3; i++) {
      reserveUseCase.reserve(
          new ReserveInventoryCommand(
              700L,
              warehouseId,
              null,
              1L,
              "step-sweep-" + i + "-" + System.nanoTime(),
              null,
              Duration.ofMinutes(15)));
    }

    // Backdate expires_at to a clearly-past timestamp (timezone-agnostic) so the sweeper
    // sees them as expired regardless of the JVM / Postgres timezone interpretation.
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    int updated =
        jdbc.update(
            "UPDATE inventory_reservation SET expires_at = '2000-01-01 00:00:00'");
    assertThat(updated).isEqualTo(3);

    // Invoke sweeper directly (bypass @Scheduled cron).
    sweeper.sweepExpired();

    // All 3 should be RELEASED now.
    Integer releasedCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_reservation WHERE status = 'RELEASED'",
            Integer.class);
    assertThat(releasedCount).isEqualTo(3);

    Integer releasedOutboxCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.released' AND aggregate_id IN (SELECT uuid FROM inventory_reservation WHERE variant_id = 700)",
            Integer.class);
    assertThat(releasedOutboxCount).isEqualTo(3);
  }
}