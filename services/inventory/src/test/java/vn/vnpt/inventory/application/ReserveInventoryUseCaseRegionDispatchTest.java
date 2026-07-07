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
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.InventoryLedgerEntryRepository;
import vn.vnpt.inventory.infrastructure.repository.InventoryReservationRepository;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Integration test for the Story 1.7 / FR-10 region dispatch on
 * {@link ReserveInventoryUseCase}. Covers the picker call path + the dispatch XOR
 * validation.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ReserveInventoryUseCaseRegionDispatchTest {

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

  @BeforeEach
  void clean() {
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void reserve_withShippingRegion_picksAndReservesViaPicker() {
    Long hcm = saveWarehouse("HCM-DISP-" + System.nanoTime(), Region.SOUTH);
    Long hn = saveWarehouse("HN-DISP-" + System.nanoTime(), Region.NORTH);
    seedOnHand(100L, hcm, 10L);

    InventoryReservation reservation =
        useCase.reserve(
            new ReserveInventoryCommand(
                100L, null, Region.SOUTH, 3L, "step-dispatch-" + System.nanoTime(), null,
                Duration.ofMinutes(15)));

    assertThat(reservation.getWarehouseId()).isEqualTo(hcm);
    assertThat(reservation.getStatus().name()).isEqualTo("ACTIVE");
  }

  @Test
  void reserve_withBothWarehouseIdAndShippingRegion_throws() {
    Long hcm = saveWarehouse("HCM-XOR-" + System.nanoTime(), Region.SOUTH);

    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        100L,
                        hcm,
                        Region.SOUTH,
                        1L,
                        "step-both-" + System.nanoTime(),
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exactly one");
  }

  @Test
  void reserve_withNeitherWarehouseIdNorShippingRegion_throws() {
    assertThatThrownBy(
            () ->
                useCase.reserve(
                    new ReserveInventoryCommand(
                        100L,
                        null,
                        null,
                        1L,
                        "step-neither-" + System.nanoTime(),
                        null,
                        Duration.ofMinutes(15))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required");
  }

  /**
   * Gap coverage — ADR-11 idempotency on the region dispatch path. The picker runs BEFORE
   * the {@code findBySagaStepId} check, so a saga retry with the same {@code sagaStepId}
   * returns the existing reservation. The picker is wasted on the retry path; that's fine —
   * the canonical authoritative state is the reservation row.
   */
  @Test
  void reserve_withShippingRegion_isIdempotentOnSagaStepId() {
    Long hcm = saveWarehouse("HCM-IDEM-" + System.nanoTime(), Region.SOUTH);
    seedOnHand(100L, hcm, 10L);
    String sagaStepId = "step-region-idem-" + System.nanoTime();

    InventoryReservation first =
        useCase.reserve(
            new ReserveInventoryCommand(
                100L, null, Region.SOUTH, 3L, sagaStepId, null, Duration.ofMinutes(15)));
    InventoryReservation second =
        useCase.reserve(
            new ReserveInventoryCommand(
                100L, null, Region.SOUTH, 3L, sagaStepId, null, Duration.ofMinutes(15)));

    // Same saga_step_id → same reservation row (ADR-11).
    assertThat(second.getUuid()).isEqualTo(first.getUuid());

    // Ledger `reserve` row written exactly once.
    Integer ledgerCount =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE reason = 'reserve' AND variant_id = 100",
                Integer.class);
    assertThat(ledgerCount).isEqualTo(1);
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