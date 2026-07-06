package vn.vnpt.inventory.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.catalog.domain.event.CatalogProductCreated;
import vn.vnpt.inventory.InventoryApplication;

/**
 * Integration test for {@link CatalogEventListener} — first in-process event consumer in the
 * platform.
 *
 * <p>Pins three invariants:
 *
 * <ul>
 *   <li>{@code onCatalogProductCreated_insertsLedgerRow} — invoking the listener with a {@code
 *       CatalogProductCreated} (empty signature = trust mode) inserts a {@code delta = 0},
 *       {@code reason = "received"} ledger row.
 *   <li>{@code onCatalogProductCreated_isIdempotent} — invoking the listener with the SAME event
 *       twice yields ONE row (the second insert hits {@code uq_inventory_ledger_event_id} and is
 *       treated as a no-op per NFR-IDEM-1).
 * </ul>
 *
 * <p>Test design: the listener is invoked directly. Modulith's {@code @ApplicationModuleListener}
 * only fires for events from a DIFFERENT module; in a single-module test context (only inventory
 * loaded), the bridge would never dispatch a same-module publication. Direct invocation tests the
 * listener's contract: HMAC verify (when configured), idempotent insert via the UNIQUE constraint,
 * default warehouse seeding. Cross-module wiring is verified at integration time when both services
 * boot together (Story 10.x for cross-process; v1 in-process works via the same bridge).
 *
 * <p>HMAC failure mode (StrictMode) is covered by
 * {@link CatalogEventListenerHmacFailureTest} so each scenario has its own Spring context (no
 * {@code @Nested} trickery — keeps Testcontainers wiring simple).
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@Testcontainers
@Transactional
@Commit
class CatalogEventListenerTest {

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

  @Autowired CatalogEventListener listener;
  @Autowired DataSource dataSource;

  @BeforeEach
  void clean() {
    new JdbcTemplate(dataSource)
        .execute("TRUNCATE TABLE inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void onCatalogProductCreated_insertsLedgerRow() {
    listener.on(new CatalogProductCreated(100L, "red-shirt", "Red Shirt", "2026-07-07T00:00:00Z"));

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE variant_id = ? AND delta = 0 AND reason = 'received'",
            Integer.class,
            100L);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void onCatalogProductCreated_isIdempotent() {
    CatalogProductCreated event =
        new CatalogProductCreated(200L, "blue-shirt", "Blue Shirt", "2026-07-07T00:00:00Z");
    listener.on(event);
    listener.on(event);

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE variant_id = ?",
            Integer.class,
            200L);
    assertThat(count).isEqualTo(1);
  }
}