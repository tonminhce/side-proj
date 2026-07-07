package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.domain.exception.ReservationNotFoundException;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.inventory.application.ReleaseInventoryUseCase;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@link AllocateInventoryUseCase} — Story 1.8 / FR-11 (ALLOCATED).
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class AllocateInventoryUseCaseTest {

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
  @Autowired AllocateInventoryUseCase allocateUseCase;
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
                    .code("HCM-01-ALLOC-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .region(Region.SOUTH).build())
            .getUuid();
  }

  private InventoryReservation seedActiveReservation(long variantId, long qty) {
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(variantId)
            .warehouseId(warehouseId)
            .delta(10L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());
    return reserveUseCase.reserve(
        new ReserveInventoryCommand(
            variantId, warehouseId, null, qty, "step-alloc-" + System.nanoTime(), null,
            Duration.ofMinutes(15)));
  }

  @Test
  void allocate_promotesActiveReservationToCommitted_andEmitsAllocated() {
    InventoryReservation reservation = seedActiveReservation(100L, 3L);

    InventoryReservation allocated =
        allocateUseCase.allocate(
            new AllocateInventoryCommand(reservation.getUuid(), "alloc-step-" + System.nanoTime()));

    assertThat(allocated.getStatus()).isEqualTo(ReservationStatus.COMMITTED);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer allocateLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'allocate'",
            Integer.class);
    assertThat(allocateLedgerCount).isEqualTo(1);

    Integer lifecycleOutboxCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'inventory.lifecycle'"
                + " AND aggregate_id = ?",
            Integer.class,
            reservation.getUuid());
    // 2 lifecycle rows: one for RESERVED (from reserveUseCase) + one for ALLOCATED.
    assertThat(lifecycleOutboxCount).isEqualTo(2);
  }

  @Test
  void allocate_isIdempotentOnAlreadyCommittedReservation_returns200_noOp() {
    InventoryReservation reservation = seedActiveReservation(101L, 2L);
    allocateUseCase.allocate(
        new AllocateInventoryCommand(reservation.getUuid(), "alloc-step-1-" + System.nanoTime()));

    // Second call: terminal-state guard → no-op
    InventoryReservation second =
        allocateUseCase.allocate(
            new AllocateInventoryCommand(reservation.getUuid(), "alloc-step-2-" + System.nanoTime()));

    assertThat(second.getStatus()).isEqualTo(ReservationStatus.COMMITTED);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer allocateLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'allocate'", Integer.class);
    assertThat(allocateLedgerCount).isEqualTo(1);
  }

  @Test
  void allocate_onUnknownReservationUuid_throwsReservationNotFoundException() {
    assertThatThrownBy(
            () ->
                allocateUseCase.allocate(
                    new AllocateInventoryCommand(999_999_999L, "alloc-step-" + System.nanoTime())))
        .isInstanceOf(ReservationNotFoundException.class);
  }

  /**
   * QA-pass gap — Story 1.6's {@code ReservationStatus.isTerminal()} returns {@code true}
   * for BOTH RELEASED and COMMITTED. The use case's terminal-state guard fires on BOTH
   * terminal states, not just COMMITTED. A regression that only guards on COMMITTED would
   * cause a re-allocate of a RELEASED reservation to silently overwrite the status and
   * double-deduct the ledger. Pin the no-op behavior on RELEASED.
   */
  @Test
  void allocate_isIdempotentOnReleasedReservation_noOp() {
    InventoryReservation reservation = seedActiveReservation(102L, 2L);
    // Release the reservation first (terminal-state path on RELEASED, not COMMITTED).
    releaseUseCase.release(reservation.getSagaStepId());

    // Re-allocate on the RELEASED reservation — must be a no-op.
    InventoryReservation reAllocated =
        allocateUseCase.allocate(
            new AllocateInventoryCommand(reservation.getUuid(), "alloc-realloc-" + System.nanoTime()));
    assertThat(reAllocated.getStatus()).isEqualTo(ReservationStatus.RELEASED);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer allocateLedgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'allocate'", Integer.class);
    assertThat(allocateLedgerCount).isEqualTo(0);
  }

  /**
   * QA-pass gap — use-case-level validation. The controller test exercises the HTTP boundary,
   * but the use-case guard (null reservationUuid → IllegalArgumentException with the field
   * name in the message) was not directly pinned.
   */
  @Test
  void allocate_throwsIllegalArgumentWhenReservationUuidIsNull() {
    assertThatThrownBy(
            () ->
                allocateUseCase.allocate(
                    new AllocateInventoryCommand(null, "alloc-step-" + System.nanoTime())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservationUuid");
  }

  /**
   * QA-pass gap — use-case-level validation. Blank sagaStepId → IllegalArgumentException.
   */
  @Test
  void allocate_throwsIllegalArgumentWhenSagaStepIdIsBlank() {
    InventoryReservation reservation = seedActiveReservation(103L, 1L);
    assertThatThrownBy(
            () -> allocateUseCase.allocate(new AllocateInventoryCommand(reservation.getUuid(), "  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sagaStepId");
  }
}