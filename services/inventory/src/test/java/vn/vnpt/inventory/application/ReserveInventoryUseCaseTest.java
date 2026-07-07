package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.domain.exception.InsufficientStockException;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for {@link ReserveInventoryUseCase} — Story 1.6 / FR-9.
 *
 * <p>Pins three invariants: (a) reserve persists the reservation + ledger row + outbox row in
 * the same transaction (ADR-04 atomicity); (b) the same {@code sagaStepId} returns the existing
 * reservation (ADR-11 idempotency); (c) {@code available < requested} → 409.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ReserveInventoryUseCaseTest {

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

  @Autowired ReserveInventoryUseCase useCase;
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
                    .code("HCM-01-RES-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .region(Region.SOUTH).build())
            .getUuid();
  }

  @Test
  void reserve_persistsReservationAndLedgerRowAndOutboxEvent() {
    // Seed on_hand = 10 (delta=+10).
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(100L)
            .warehouseId(warehouseId)
            .delta(10L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    InventoryReservation reservation =
        useCase.reserve(
            new ReserveInventoryCommand(
                100L,
                warehouseId,
                null,
                3L,
                "step-happy-" + System.nanoTime(),
                42L,
                Duration.ofMinutes(15)));

    assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
    assertThat(reservation.getQuantity()).isEqualTo(3L);
    assertThat(reservation.getOrderUuid()).isEqualTo(42L);
    assertThat(reservation.getExpiresAt()).isAfter(Instant.now());

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer reservationCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_reservation WHERE uuid = ?",
            Integer.class,
            reservation.getUuid());
    assertThat(reservationCount).isEqualTo(1);

    // Ledger row: delta=-3, reason='reserve'.
    Integer ledgerReserveCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'reserve' AND variant_id = 100",
            Integer.class);
    assertThat(ledgerReserveCount).isEqualTo(1);

    // Outbox row: aggregate_type='InventoryReservation', event_type='inventory.reserved'.
    Map<String, Object> outboxRow =
        jdbc.queryForMap(
            "SELECT aggregate_type, event_type, payload, signatures FROM outbox WHERE aggregate_id = ?",
            reservation.getUuid());
    assertThat(outboxRow.get("aggregate_type")).isEqualTo("InventoryReservation");
    assertThat(outboxRow.get("event_type")).isEqualTo("inventory.reserved");
    assertThat(outboxRow.get("payload")).isNotNull();
    assertThat(outboxRow.get("signatures")).isNotNull();
  }

  @Test
  void reserve_isIdempotentOnSagaStepId() {
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(200L)
            .warehouseId(warehouseId)
            .delta(5L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    String sagaStepId = "step-idem-" + System.nanoTime();

    InventoryReservation first =
        useCase.reserve(
            new ReserveInventoryCommand(
                200L, warehouseId, null, 1L, sagaStepId, null, Duration.ofMinutes(15)));
    InventoryReservation second =
        useCase.reserve(
            new ReserveInventoryCommand(
                200L, warehouseId, null, 1L, sagaStepId, null, Duration.ofMinutes(15)));

    // Same saga_step_id → same reservation row.
    assertThat(second.getUuid()).isEqualTo(first.getUuid());

    // Ledger row written exactly once.
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer ledgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'reserve' AND variant_id = 200",
            Integer.class);
    assertThat(ledgerCount).isEqualTo(1);
  }

  @Test
  void reserve_throwsIllegalArgumentWhenQuantityIsZero() {
    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        100L,
                        warehouseId,
                        null,
                        0L,
                        "step-qty-zero-" + System.nanoTime(),
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void reserve_throwsIllegalArgumentWhenQuantityIsNegative() {
    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        100L,
                        warehouseId,
                        null,
                        -1L,
                        "step-qty-neg-" + System.nanoTime(),
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quantity");
  }

  @Test
  void reserve_throwsIllegalArgumentWhenSagaStepIdIsBlank() {
    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(100L, warehouseId, null, 1L, "  ",
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sagaStepId");
  }

  @Test
  void reserve_throwsIllegalArgumentWhenTtlIsNegative() {
    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        100L, warehouseId, null, 1L, "step-ttl-neg-" + System.nanoTime(), null,
                        Duration.ofMinutes(-1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ttl");
  }

  @Test
  void reserve_throwsWarehouseNotFoundForUnknownWarehouseId() {
    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        100L,
                        9_999_999L,
                        null,
                        1L,
                        "step-wh-nf-" + System.nanoTime(),
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(vn.vnpt.inventory.domain.exception.WarehouseNotFoundException.class);
  }

  @Test
  void reserve_throwsInsufficientStockWhenAvailableLessThanRequested() {
    ledgerRepository.save(
        InventoryLedgerEntry.builder()
            .variantId(300L)
            .warehouseId(warehouseId)
            .delta(1L)
            .reason(InventoryReason.RECEIVE.toColumnValue())
            .eventId(SnowflakeIdGenerator.generateId())
            .tenantId("default")
            .build());

    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        300L,
                        warehouseId,
                        null,
                        5L,
                        "step-insufficient-" + System.nanoTime(),
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(InsufficientStockException.class)
        .satisfies(
            e -> {
              InsufficientStockException ex = (InsufficientStockException) e;
              assertThat(ex.getRequested()).isEqualTo(5L);
              assertThat(ex.getAvailable()).isEqualTo(1L);
            });
  }
}