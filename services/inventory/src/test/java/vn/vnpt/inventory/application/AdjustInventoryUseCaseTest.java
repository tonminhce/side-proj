package vn.vnpt.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import vn.vnpt.inventory.domain.Warehouse;
import vn.vnpt.inventory.domain.exception.WarehouseNotFoundException;
import vn.vnpt.inventory.infrastructure.repository.WarehouseRepository;

/**
 * Integration test for {@link AdjustInventoryUseCase} — full Spring context with Testcontainers
 * Postgres. Pins four invariants:
 *
 * <ul>
 *   <li>{@code adjust} persists the ledger row + outbox event in the SAME transaction (ADR-04
 *       atomicity)
 *   <li>zero delta is rejected with {@link IllegalArgumentException}
 *   <li>unknown warehouse id is rejected with {@link WarehouseNotFoundException}
 *   <li>negative deltas are ALLOWED (Story 1.6 is the canonical oversell guard, NOT this use
 *       case — pin the YAGNI deviation)
 * </ul>
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class AdjustInventoryUseCaseTest {

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

  private Long warehouseId;

  @BeforeEach
  void seedWarehouse() {
    new JdbcTemplate(dataSource).execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
    Warehouse seeded =
        warehouseRepository.save(
            Warehouse.builder().code("HCM-01-UC-" + System.nanoTime()).displayName("Ho Chi Minh").region(Region.SOUTH).build());
    warehouseId = seeded.getUuid();
  }

  @Test
  void adjust_persistsLedgerRowAndOutboxEvent() {
    InventoryLedgerEntry saved =
        useCase.adjust(new AdjustInventoryCommand(100L, warehouseId, 5L, InventoryReason.RECEIVE));

    assertThat(saved.getDelta()).isEqualTo(5L);
    assertThat(saved.getReason()).isEqualTo("receive");
    assertThat(saved.getEventId()).isNotNull().isPositive();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer ledgerCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE event_id = ?",
            Integer.class,
            saved.getEventId());
    assertThat(ledgerCount).isEqualTo(1);

    Map<String, Object> outboxRow =
        jdbc.queryForMap(
            "SELECT aggregate_type, aggregate_id, event_type FROM outbox WHERE aggregate_id = ?",
            saved.getUuid());
    assertThat(outboxRow.get("aggregate_type")).isEqualTo("InventoryLedger");
    assertThat(((Number) outboxRow.get("aggregate_id")).longValue()).isEqualTo(saved.getUuid());
    // Story 1.8: events emit to unified `inventory.lifecycle` topic (with phase=ADJUSTED).
    assertThat(outboxRow.get("event_type")).isEqualTo("inventory.lifecycle");
  }

  @Test
  void adjust_rejectsZeroDelta() {
    assertThatThrownBy(
            () -> useCase.adjust(new AdjustInventoryCommand(100L, warehouseId, 0L, InventoryReason.RECEIVE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("delta");
  }

  @Test
  void adjust_throwsWhenWarehouseNotFound() {
    assertThatThrownBy(
            () -> useCase.adjust(new AdjustInventoryCommand(100L, 99_999L, 1L, InventoryReason.RECEIVE)))
        .isInstanceOf(WarehouseNotFoundException.class);
  }

  /**
   * AC #12 — Story 1.5 does NOT enforce {@code on_hand >= 0}. Negative deltas are valid for
   * "adjust" reasons (lost-in-warehouse). Story 1.6's reservation path is the canonical
   * oversell guard. Pin this YAGNI deviation explicitly.
   */
  @Test
  void adjust_allowsNegativeDelta() {
    InventoryLedgerEntry saved =
        useCase.adjust(new AdjustInventoryCommand(100L, warehouseId, -1L, InventoryReason.ADJUST));

    assertThat(saved.getDelta()).isEqualTo(-1L);
    assertThat(saved.getReason()).isEqualTo("adjust");
  }

  /**
   * AC #6 / AC #8 — the ledger row carries the v1 single-tenant default. The entity's
   * {@code @PrePersist} sets {@code tenantId = "default"} if null at insert time. Pin the
   * invariant: app callers may pass {@code null} for tenantId (none do today) and the
   * schema/contract is preserved.
   */
  @Test
  void adjust_persistsDefaultTenantId() {
    InventoryLedgerEntry saved =
        useCase.adjust(
            new AdjustInventoryCommand(100L, warehouseId, 5L, InventoryReason.RECEIVE));

    assertThat(saved.getTenantId()).isEqualTo("default");
  }

  /**
   * Story 1.8 / FR-11 — adjust emits an {@code ADJUSTED} lifecycle event on the unified
   * {@code inventory.lifecycle} topic (NOT the legacy {@code inventory.adjust} topic).
   */
  @Test
  void adjust_emitsAdjustedLifecycleEvent() {
    InventoryLedgerEntry saved =
        useCase.adjust(new AdjustInventoryCommand(200L, warehouseId, 5L, InventoryReason.RECEIVE));

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT event_type, aggregate_id FROM outbox WHERE aggregate_id = ?",
            saved.getUuid());
    assertThat(row.get("event_type")).isEqualTo("inventory.lifecycle");
    assertThat(((Number) row.get("aggregate_id")).longValue()).isEqualTo(saved.getUuid());
  }

  /**
   * QA-pass gap — the outbox payload JSON MUST carry {@code "phase":"ADJUSTED"}. The
   * existing {@link #adjust_emitsAdjustedLifecycleEvent()} only asserts the {@code event_type}
   * column; a regression that flipped the phase to {@code "RECEIVE"} (since
   * {@code InventoryReason.RECEIVE} is the test's reason) would still pass {@code event_type
   * ="inventory.lifecycle"} but downstream consumers filtering on phase would silently
   * lose the event. Pin the JSON payload's phase field directly.
   */
  @Test
  void adjust_outboxPayloadCarriesPhaseAdjusted() {
    InventoryLedgerEntry saved =
        useCase.adjust(new AdjustInventoryCommand(201L, warehouseId, -2L, InventoryReason.ADJUST));

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE aggregate_id = ?",
            String.class,
            saved.getUuid());
    // postgres jsonb normalizes whitespace (space after colon); assert on each field key + value
    // separately rather than the full key-value pair.
    assertThat(payload).contains("\"phase\"").contains("ADJUSTED");
    assertThat(payload).contains("\"aggregateType\"").contains("InventoryLedger");
    assertThat(payload).contains("\"reason\"").contains("adjust");
    // ADR-20: signatures column populated.
    String signatures =
        jdbc.queryForObject(
            "SELECT signatures::text FROM outbox WHERE aggregate_id = ?",
            String.class,
            saved.getUuid());
    assertThat(signatures).contains("hmac_sha256");
  }
}