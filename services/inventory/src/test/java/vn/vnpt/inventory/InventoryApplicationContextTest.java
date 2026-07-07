package vn.vnpt.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
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

/**
 * Smoke test — the InventoryService Spring context boots end-to-end against a real Postgres
 * (Testcontainers) and Flyway applies V001 + V002. If this test passes, AC #22 (context refresh)
 * and AC #19 (Spring context test) are satisfied.
 *
 * <p>Beyond {@link #contextLoads()}, four invariants are pinned that the bare context-load check
 * would miss: (a) the DataSource targets {@code inventory_db} — ADR-03's database-per-service
 * boundary; (b) Flyway applied V001 — silent {@code flyway.enabled: false} would still pass
 * context load; (c) the canonical schema is present; (d) the {@code uq_inventory_ledger_event_id}
 * UNIQUE constraint is intact — both cross-service contracts.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
class InventoryApplicationContextTest {

  @Container
  @SuppressWarnings("resource") // Testcontainers lifecycle managed by @Testcontainers
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("inventory_db")
          .withUsername("inventory_user")
          .withPassword("inventory_pass");

  @DynamicPropertySource
  static void registerPostgresProperties(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired DataSource dataSource;

  @Test
  void contextLoads() {
    // Spring's @SpringBootTest fails this test if the context cannot refresh.
  }

  /** ADR-03 — DataSource MUST target {@code inventory_db}. */
  @Test
  void datasourceTargetsInventoryDatabase() throws Exception {
    String url = dataSource.getConnection().getMetaData().getURL();
    assertThat(url).matches("jdbc:postgresql://[^/]+/inventory_db(\\?.*)?");
  }

  /** Flyway MUST have applied V001. */
  @Test
  void flywayAppliedV001() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '001'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  /** Story 1.6 — Flyway MUST have applied V003 (reservation table). */
  @Test
  void flywayAppliedV003() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '003'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  /** Story 1.6 — Flyway MUST have applied V004 (outbox signatures column). */
  @Test
  void flywayAppliedV004() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '004'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  /** Story 1.7 — Flyway MUST have applied V005 (warehouses.region column + seeds). */
  @Test
  void flywayAppliedV005() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '005'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  /**
   * Story 1.7 — V005 seeds HCM-01 (region=SOUTH) + HN-01 (region=NORTH). Migration's intent
   * is to give FR-10 dispatch two real warehouses to pick from. A regression that drops the
   * INSERTs (or ships the wrong region) breaks the dev smoke; this test catches it at the
   * Spring context boot.
   */
  @Test
  void v005SeedsHcmAndHnWithCorrectRegions() {
    Map<String, String> regions =
        new JdbcTemplate(dataSource)
            .queryForList(
                "SELECT code, region::text AS region FROM warehouses WHERE code IN ('HCM-01', 'HN-01')")
            .stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    row -> (String) row.get("code"), row -> (String) row.get("region")));
    assertThat(regions).containsEntry("HCM-01", "SOUTH").containsEntry("HN-01", "NORTH");
  }

  /** V001 MUST create the canonical 4 business tables + Flyway's bookkeeping table; Story 1.6 adds inventory_reservation via V003. */
  @Test
  void allExpectedTablesExist() {
    Set<String> tables =
        new JdbcTemplate(dataSource)
            .queryForList(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class)
            .stream()
            .collect(Collectors.toSet());
    assertThat(tables)
        .containsExactlyInAnyOrder(
            "warehouses",
            "inventory_ledger",
            "inventory_reservation",
            "outbox",
            "processed_event",
            "flyway_schema_history");
  }

  /**
   * ADR-04 / AC #6 — the {@code uq_inventory_ledger_event_id} UNIQUE constraint is the
   * cross-aggregate idempotency key for inbound catalog events. The constraint must be intact
   * or downstream consumers could double-process events.
   */
  @Test
  void inventoryLedgerEventIdHasUniqueConstraint() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints"
                    + " WHERE table_name = 'inventory_ledger' AND constraint_type = 'UNIQUE'"
                    + " AND constraint_name = 'uq_inventory_ledger_event_id'",
                Integer.class);
    assertThat(count).isEqualTo(1);
  }

  /**
   * AC #10 — the {@code inventory_on_hand} VIEW is the debugging surface for the
   * sum-derivation. The production read path goes through JPA (see {@code
   * OnHandUseCase}); the view exists for ops. Verify V002 applied + the view aggregates
   * correctly — the column shape ({@code on_hand}, {@code entry_count}, {@code
   * last_movement_at}) is the contract for any future ops query.
   */
  @Test
  void inventoryOnHandViewExistsAndAggregates() {
    // Seed two warehouses + a few ledger entries directly via SQL so we own the test data.
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Long whA =
        ((Number)
                jdbc.queryForMap(
                    "INSERT INTO warehouses (uuid, code, display_name, region) VALUES (?, ?, ?, ?) RETURNING uuid",
                    System.nanoTime(),
                    "VIEW-A-" + System.nanoTime(),
                    "View A",
                    "SOUTH")
            .get("uuid"))
            .longValue();
    Long whB =
        ((Number)
                jdbc.queryForMap(
                    "INSERT INTO warehouses (uuid, code, display_name, region) VALUES (?, ?, ?, ?) RETURNING uuid",
                    System.nanoTime(),
                    "VIEW-B-" + System.nanoTime(),
                    "View B",
                    "NORTH")
            .get("uuid"))
            .longValue();
    jdbc.update(
        "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        System.nanoTime(),
        777L,
        whA,
        5L,
        "receive",
        System.nanoTime());
    jdbc.update(
        "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        System.nanoTime(),
        777L,
        whA,
        -2L,
        "ship",
        System.nanoTime());
    jdbc.update(
        "INSERT INTO inventory_ledger (uuid, variant_id, warehouse_id, delta, reason, event_id)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        System.nanoTime(),
        777L,
        whB,
        10L,
        "receive",
        System.nanoTime());

    // View exists in information_schema.views.
    Integer viewCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.views"
                + " WHERE table_schema = 'public' AND table_name = 'inventory_on_hand'",
            Integer.class);
    assertThat(viewCount).isEqualTo(1);

    // Aggregation matches the JPA query: variant 777 split across whA and whB.
    Map<String, Object> rowA =
        jdbc.queryForMap(
            "SELECT on_hand, entry_count FROM inventory_on_hand"
                + " WHERE variant_id = ? AND warehouse_id = ?",
            777L,
            whA);
    assertThat(((Number) rowA.get("on_hand")).longValue()).isEqualTo(3L);
    assertThat(((Number) rowA.get("entry_count")).longValue()).isEqualTo(2L);

    Map<String, Object> rowB =
        jdbc.queryForMap(
            "SELECT on_hand, entry_count FROM inventory_on_hand"
                + " WHERE variant_id = ? AND warehouse_id = ?",
            777L,
            whB);
    assertThat(((Number) rowB.get("on_hand")).longValue()).isEqualTo(10L);
    assertThat(((Number) rowB.get("entry_count")).longValue()).isEqualTo(1L);
  }
}