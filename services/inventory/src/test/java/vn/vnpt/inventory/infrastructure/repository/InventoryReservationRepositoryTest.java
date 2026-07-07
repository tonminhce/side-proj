package vn.vnpt.inventory.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.Region;
import vn.vnpt.inventory.domain.ReservationStatus;
import vn.vnpt.inventory.domain.Warehouse;

/**
 * Integration test for {@link InventoryReservationRepository} — Story 1.6 / FR-9.
 *
 * <p>Pins the ADR-11 idempotency lookup ({@code findBySagaStepId}) and the sweeper query
 * ({@code findByStatusAndExpiresAtBefore}).
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class InventoryReservationRepositoryTest {

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

  @Autowired InventoryReservationRepository reservationRepository;
  @Autowired WarehouseRepository warehouseRepository;
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
                    .code("HCM-01-REPO-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .region(Region.SOUTH).build())
            .getUuid();
  }

  @Test
  void findBySagaStepId_returnsExistingReservation() {
    InventoryReservation saved =
        reservationRepository.save(
            InventoryReservation.builder()
                .variantId(800L)
                .warehouseId(warehouseId)
                .quantity(1L)
                .status(ReservationStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(900))
                .sagaStepId("step-repo-lookup-" + System.nanoTime())
                .tenantId("default")
                .build());

    var found = reservationRepository.findBySagaStepId(saved.getSagaStepId());
    assertThat(found).isPresent();
    assertThat(found.get().getUuid()).isEqualTo(saved.getUuid());
  }

  @Test
  void findByStatusAndExpiresAtBefore_returnsExpiredActiveReservations() {
    // One expired ACTIVE, one future ACTIVE.
    InventoryReservation expired =
        reservationRepository.save(
            InventoryReservation.builder()
                .variantId(800L)
                .warehouseId(warehouseId)
                .quantity(1L)
                .status(ReservationStatus.ACTIVE)
                .expiresAt(Instant.now().minusSeconds(60))
                .sagaStepId("step-expired-" + System.nanoTime())
                .tenantId("default")
                .build());
    reservationRepository.save(
        InventoryReservation.builder()
            .variantId(800L)
            .warehouseId(warehouseId)
            .quantity(1L)
            .status(ReservationStatus.ACTIVE)
            .expiresAt(Instant.now().plusSeconds(900))
            .sagaStepId("step-future-" + System.nanoTime())
            .tenantId("default")
            .build());

    var results =
        reservationRepository.findByStatusAndExpiresAtBefore(
            ReservationStatus.ACTIVE, Instant.now());

    assertThat(results).extracting(InventoryReservation::getUuid).contains(expired.getUuid());
    assertThat(results).hasSize(1);
  }
}