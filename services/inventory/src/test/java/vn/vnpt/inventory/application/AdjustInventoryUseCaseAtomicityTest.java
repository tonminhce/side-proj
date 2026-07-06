package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.inventory.InventoryApplication;
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.inventory.domain.InventoryReason;
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;

/**
 * ADR-04 atomicity guard — Story 1.5 / AC #12. Pins the invariant that the business
 * ledger write and the outbox row are in the SAME transaction. If the outbox throws, the
 * ledger row MUST be rolled back (and vice versa). A regression that drops {@code
 * @Transactional} or separates the two writes would silently publish events without
 * committing state — a subtle data-integrity bug.
 *
 * <p>Strategy: mock the {@link OutboxPublisher} port to throw after the ledger save.
 * The use case's {@code @Transactional} must propagate the rollback, leaving the
 * {@code inventory_ledger} empty for the variant.
 *
 * <p>NOTE: this test class is split from {@link AdjustInventoryUseCaseTest} because
 * {@code @MockitoBean} replaces the real {@code ModulithOutboxPublisher}; the existing
 * happy-path test asserts that the real publisher writes the outbox row, which would
 * fail under the mock. Splitting keeps each test focused on a single invariant.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class AdjustInventoryUseCaseAtomicityTest {

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

  @Autowired AdjustInventoryUseCase useCase;
  @Autowired WarehouseRepository warehouseRepository;
  @Autowired DataSource dataSource;

  /** Forced to throw on append — use case's transaction must roll back the ledger save. */
  @MockitoBean OutboxPublisher outboxPublisher;

  private Long warehouseId;

  @BeforeEach
  void seedWarehouse() {
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_ledger, warehouses RESTART IDENTITY");
    warehouseId =
        warehouseRepository
            .save(
                Warehouse.builder()
                    .code("HCM-01-ATOMIC-" + System.nanoTime())
                    .displayName("Ho Chi Minh")
                    .build())
            .getUuid();
  }

  @Test
  void adjust_rollsBackLedgerWhenOutboxThrows() {
    doThrow(new IllegalStateException("simulated outbox failure"))
        .when(outboxPublisher)
        .append(anyString(), anyLong(), anyString(), any(), anyMap());

    assertThatThrownBy(
            () ->
                useCase.adjust(
                    new AdjustInventoryCommand(100L, warehouseId, 5L, InventoryReason.RECEIVE)))
        .isInstanceOf(IllegalStateException.class);

    // ADR-04 atomicity: the ledger row that was saved BEFORE the outbox threw MUST be
    // rolled back. A row persisting here would mean the @Transactional boundary is wrong
    // and downstream consumers could see ghost events.
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer ledgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE variant_id = ?", Integer.class, 100L);
    assertThat(ledgerCount).isEqualTo(0);

    Integer outboxCount =
        jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    assertThat(outboxCount).isEqualTo(0);
  }
}