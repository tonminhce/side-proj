package vn.vnpt.inventory.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.catalog.domain.event.CatalogProductCreated;
import vn.vnpt.inventory.InventoryApplication;

/**
 * HMAC failure path for {@link CatalogEventListener} — non-empty signature that doesn't match the
 * listener envelope must cause the listener to skip the insert. ADR-20 consumer half.
 *
 * <p>Lives in a separate top-level test class (not {@code @Nested}) so each scenario has its own
 * Spring context with the right {@code catalog.events.signature} property — the
 * {@code @Container} + {@code @DynamicPropertySource} wiring stays simple.
 */
@SpringBootTest(classes = InventoryApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {"catalog.events.signature=tampered-signature-value"})
@Testcontainers
class CatalogEventListenerHmacFailureTest {

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
        .execute("TRUNCATE TABLE inventory_reservation, inventory_ledger, warehouses RESTART IDENTITY");
  }

  @Test
  void onCatalogProductCreated_skipsOnHmacFailure() {
    listener.on(new CatalogProductCreated(300L, "green-shirt", "Green Shirt", "2026-07-07T00:00:00Z"));

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM inventory_ledger WHERE variant_id = ?",
            Integer.class,
            300L);
    assertThat(count).isEqualTo(0);
  }
}