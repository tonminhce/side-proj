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
}
