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
import vn.vnpt.inventory.domain.InventoryLedgerEntry;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@link ReleaseInventoryUseCase} — Story 1.6 / FR-9.
 *
 * <p>Pins two invariants: (a) {@code release(sagaStepId)} flips the reservation to RELEASED
 * and writes a {@code reason='release'} ledger row; (b) {@code releaseExpired(reservationUuid)}
 * is idempotent (sweeper re-runs on already-released reservations are no-ops).
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ReleaseInventoryUseCaseTest {

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
  @Autowired ReleaseInventoryUseCase releaseUseCase;
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
                    .code("HCM-01-REL-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .build())
            .getUuid();
  }

  @Test
  void release_bySagaStepId_marksReservationReleased() {
    // Seed ledger + reserve.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(500L)
            .warehouseId(warehouseId)
            .delta(5L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    String sagaStepId = "step-rel-" + System.nanoTime();
    InventoryReservation reservation =
        reserveUseCase.reserve(
            new ReserveInventoryCommand(
                500L, warehouseId, 2L, sagaStepId, null, Duration.ofMinutes(15)));

    releaseUseCase.release(sagaStepId);

    InventoryReservation reloaded = reservationRepository.findById(reservation.getUuid()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.RELEASED);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer releaseLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'release' AND variant_id = 500",
            Integer.class);
    assertThat(releaseLedgerCount).isEqualTo(1);

    Integer releasedOutboxCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.released' AND aggregate_id = ?",
            Integer.class,
            reservation.getUuid());
    assertThat(releasedOutboxCount).isEqualTo(1);
  }

  @Test
  void release_unknownSagaStepId_isNoOp() {
    // No prior reservation — must NOT throw; saga retry-with-bad-step is a silent no-op.
    releaseUseCase.release("step-unknown-" + System.nanoTime());

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer releaseLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'release'", Integer.class);
    assertThat(releaseLedgerCount).isEqualTo(0);
  }

  @Test
  void release_alreadyReleasedReservation_isNoOp() {
    // Seed + reserve + release once.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(700L)
            .warehouseId(warehouseId)
            .delta(5L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
    String sagaStepId = "step-twice-" + System.nanoTime();
    reserveUseCase.reserve(
        new ReserveInventoryCommand(
            700L, warehouseId, 1L, sagaStepId, null, Duration.ofMinutes(15)));
    releaseUseCase.release(sagaStepId);

    // Second release call: terminal-state guard → no second ledger row.
    releaseUseCase.release(sagaStepId);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer releaseLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'release' AND variant_id = 700",
            Integer.class);
    assertThat(releaseLedgerCount).isEqualTo(1);
  }

  @Test
  void releaseExpired_byUuid_marksReservationReleased() {
    // Seed + reserve.
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(600L)
            .warehouseId(warehouseId)
            .delta(5L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
    InventoryReservation reservation =
        reserveUseCase.reserve(
            new ReserveInventoryCommand(
                600L,
                warehouseId,
                1L,
                "step-rel-exp-" + System.nanoTime(),
                null,
                Duration.ofMinutes(15)));

    releaseUseCase.releaseExpired(reservation.getUuid());

    InventoryReservation reloaded = reservationRepository.findById(reservation.getUuid()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(ReservationStatus.RELEASED);

    // Second call is idempotent — no second ledger row.
    releaseUseCase.releaseExpired(reservation.getUuid());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer releaseLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'release' AND variant_id = 600",
            Integer.class);
    assertThat(releaseLedgerCount).isEqualTo(1);
  }
}