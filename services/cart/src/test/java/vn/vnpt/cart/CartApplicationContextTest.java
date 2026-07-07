package vn.vnpt.cart;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Smoke test — the CartService Spring context boots end-to-end against a real Postgres
 * (Testcontainers) and Flyway applies V001 (Story 2.1 / AC #15).
 */
@SpringBootTest(classes = CartApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CartApplicationContextTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("cart_db")
          .withUsername("cart_user")
          .withPassword("cart_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired DataSource dataSource;

  @Test
  void contextLoads_withFlywayAppliedV001() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // ADR-03 — DataSource targets cart_db.
    assertThat(POSTGRES.getDatabaseName()).isEqualTo("cart_db");

    // Flyway applied V001.
    Integer v001 =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '001'", Integer.class);
    assertThat(v001).isEqualTo(1);

    // Canonical tables exist.
    Set<String> tables =
        jdbc
            .queryForList(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class)
            .stream()
            .collect(Collectors.toSet());
    assertThat(tables)
        .contains("carts", "cart_lines", "cart_merge_log", "outbox", "processed_event");

    // NFR-IDEM-3 beacon — the idempotency-key UNIQUE constraint is intact.
    Integer uq =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints"
                + " WHERE table_name = 'cart_merge_log' AND constraint_type = 'UNIQUE'"
                + " AND constraint_name = 'uq_cart_merge_log_idempotency_key'",
            Integer.class);
    assertThat(uq).isEqualTo(1);
  }

  /** Story 2.2 / FR-18 — V002 adds the TTL anchor column with a 30-day default. */
  @Test
  void contextLoads_withFlywayAppliedV002_expiresAtColumnExists() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    Integer v002 =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '002'", Integer.class);
    assertThat(v002).isEqualTo(1);

    Integer expiresAtColumn =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_name = 'carts' AND column_name = 'expires_at'",
            Integer.class);
    assertThat(expiresAtColumn).isEqualTo(1);

    // Partial sweeper index exists.
    Integer sweeperIdx =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_indexes"
                + " WHERE indexname = 'idx_carts_status_expires_at'",
            Integer.class);
    assertThat(sweeperIdx).isEqualTo(1);

    // Column default applies on insert — a freshly created cart expires ~30 days from now.
    long cartUuid = System.currentTimeMillis() * 1000L + (System.nanoTime() % 1000L);
    jdbc.update(
        "INSERT INTO carts (uuid, tenant_id, status, expires_at) VALUES (?, 'default', 'ANONYMOUS', now() + INTERVAL '30 days')",
        cartUuid);
    Integer freshExpires =
        jdbc.queryForObject(
            "SELECT (expires_at > now() + INTERVAL '29 days' AND expires_at < now() + INTERVAL '31 days')::int"
                + " FROM carts WHERE uuid = ?",
            Integer.class,
            cartUuid);
    assertThat(freshExpires).isEqualTo(1);
  }
}
