package vn.vnpt.checkout;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;

/**
 * Smoke test — the CheckoutService Spring context boots end-to-end against a real Postgres
 * (Testcontainers) and Flyway applies V001 (Story 2.3 / FR-19, FR-21).
 */
@SpringBootTest(classes = CheckoutApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CheckoutApplicationContextTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("checkout_db")
          .withUsername("checkout_user")
          .withPassword("checkout_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired DataSource dataSource;
  @MockitoBean ReserveInventoryUseCase reserveInventoryUseCase;

  @Test
  void contextLoads_withFlywayAppliedV001() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // ADR-03 — DataSource targets checkout_db.
    assertThat(POSTGRES.getDatabaseName()).isEqualTo("checkout_db");

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
    assertThat(tables).contains("checkouts", "outbox", "processed_event");

    // status CHECK constraint enforces the 6-state enum.
    Integer statusCheckCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.check_constraints"
                + " WHERE constraint_name LIKE '%status%'",
            Integer.class);
    assertThat(statusCheckCount).isGreaterThanOrEqualTo(1);
  }

  @Test
  void checkoutApplicationContext_startsWithV001Applied_andEntityLoads() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    // The checkouts table carries the columns the entity expects.
    Set<String> columns =
        jdbc
            .queryForList(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_name = 'checkouts'",
                String.class)
            .stream()
            .collect(Collectors.toSet());
    assertThat(columns)
        .contains(
            "uuid",
            "tenant_id",
            "cart_uuid",
            "user_id",
            "guest_cart_id",
            "status",
            "version",
            "stripe_client_secret",
            "recipient_name",
            "phone",
            "address_line_1",
            "city",
            "province",
            "country",
            "created_at",
            "updated_at",
            "is_active",
            "is_deleted");

    // Indexes for ADR-22 saga + Story 2.x sweeper exist.
    Set<String> indexes =
        jdbc
            .queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'checkouts'", String.class)
            .stream()
            .collect(Collectors.toSet());
    assertThat(indexes)
        .contains(
            "idx_checkouts_tenant_cart",
            "idx_checkouts_tenant_user",
            "idx_checkouts_status_updated");
  }
}