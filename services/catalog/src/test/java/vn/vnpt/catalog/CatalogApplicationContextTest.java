package vn.vnpt.catalog;

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
 * Smoke test — the CatalogService Spring context boots end-to-end against a real Postgres
 * (Testcontainers) and Flyway applies {@code V001__create_catalog_tables.sql}. If this test
 * passes, AC #11 (context refresh) and AC #9 (Spring context test) are satisfied.
 *
 * <p>Why Testcontainers over H2: AC #11 prefers Postgres so the Flyway migration is exercised
 * against the same dialect as production. {@code postgres:16-alpine} matches the dev compose.
 * Fallback to H2 with PostgreSQL mode is documented in Story 1.1 Subtask 6.3.
 *
 * <p>Beyond {@link #contextLoads()}, the post-context tests pin three invariants that the bare
 * context-load check would miss: (a) the DataSource targets {@code catalog_db} — ADR-03's
 * database-per-service boundary, (b) Flyway actually applied V001 — silent {@code flyway.enabled:
 * false} would still pass context load, (c) the canonical schema is present and the
 * {@code outbox.event_id} UNIQUE constraint from ADR-04 is intact — both cross-service contracts.
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CatalogApplicationContextTest {

  @Container
  @SuppressWarnings("resource") // Testcontainers lifecycle managed by @Testcontainers
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("catalog_db")
          .withUsername("catalog_user")
          .withPassword("catalog_pass");

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
    // CatalogApplication + autoconfig (util's UtilsAutoConfiguration) + Flyway + datasource
    // are all wired by the application.yml hierarchy. No assertions needed here.
  }

  /** ADR-03 — DataSource MUST target {@code catalog_db}. A typo in {@code POSTGRES_CATALOG_DB} would
   * still pass {@code contextLoads} because Spring connects to whatever URL is provided; this
   * check pins the per-service database as a regression guard. */
  @Test
  void datasourceTargetsCatalogDatabase() throws Exception {
    String url = dataSource.getConnection().getMetaData().getURL();
    assertThat(url).matches("jdbc:postgresql://[^/]+/catalog_db(\\?.*)?");
  }

  /** Flyway MUST have applied V001. If {@code spring.flyway.enabled} is silently flipped to {@code
   * false} (or the migration directory is mis-pointed), {@code contextLoads} still passes. This
   * check reads {@code flyway_schema_history} directly to prove V001 ran. */
  @Test
  void flywayAppliedV001() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '001'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  /** V001 MUST create the canonical 5 tables + Flyway's own bookkeeping table. AC #6 names these
   * by hand. A future migration that drops or renames one would not fail {@code contextLoads}; this
   * check pins the table set. */
  @Test
  void allExpectedTablesExist() {
    Set<String> tables =
        new JdbcTemplate(dataSource)
            .queryForList(
                "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public'",
                String.class)
            .stream()
            .collect(Collectors.toSet());
    assertThat(tables)
        .containsExactlyInAnyOrder(
            "products",
            "variants",
            "attributes",
            "outbox",
            "processed_event",
            "flyway_schema_history");
  }

  /** ADR-04 — consumer idempotency. {@code outbox.event_id} is the cross-service idempotency key;
   * the UNIQUE constraint must be intact or downstream consumers could double-process events. */
  @Test
  void outboxEventIdHasUniqueConstraint() {
    Integer count =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints"
                    + " WHERE table_name = 'outbox' AND constraint_type = 'UNIQUE'"
                    + " AND constraint_name LIKE '%event_id%'",
                Integer.class);
    assertThat(count).isGreaterThanOrEqualTo(1);
  }

  /**
   * AC #9 + Story 1.2 V002 back-fill — V002 must have applied: (a) the {@code tenant_id} column on
   * the 3 business tables with the DEFAULT 'default' (architecture-detail.md line 78), and (b) the
   * audit columns that BaseEntity/RootEntity require. A future migration that drops one would
   * cause {@code ddl-auto=validate} to refuse to boot — but a missing migration leaves a stale
   * schema that boots fine. Pin both invariants.
   */
  @Test
  void v002AppliedTenantIdAndAuditColumns() {
    Set<String> cols =
        new JdbcTemplate(dataSource)
            .queryForList(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = 'public' AND table_name = 'variants'",
                String.class)
            .stream()
            .collect(Collectors.toSet());
    // AC #9: tenant_id present.
    assertThat(cols).contains("tenant_id");
    // V002 back-fill: RootEntity audit columns on variants.
    assertThat(cols)
        .as("variants audit columns required by RootEntity inheritance")
        .contains("id", "created_by", "updated_by", "deleted_by", "deleted_at", "is_active");
  }

  /**
   * Story 1.3 / AC #7 / ADR-20 — V003 must have applied the {@code signatures} JSONB column on
   * the {@code outbox} table. The HMAC event signer writes its output there; a regression that
   * drops the column would only fail at INSERT time, not at boot, and the bridge test would
   * diagnose it as "publisher bug" rather than "DDL drift". Pin the column existence here.
   */
  @Test
  void v003AppliedOutboxSignaturesColumn() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Set<String> outboxCols =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'outbox'",
            String.class)
            .stream()
            .collect(Collectors.toSet());
    assertThat(outboxCols)
        .as("outbox.signatures JSONB column required by ADR-20 producer-side HMAC")
        .contains("signatures");

    String dataType =
        jdbc.queryForObject(
            "SELECT data_type FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'outbox'"
                + " AND column_name = 'signatures'",
            String.class);
    assertThat(dataType).isEqualTo("jsonb");

    // Nullable: pre-V003 rows have NULL signatures (the consumer falls back to "unsigned =
    // log a warning" per ModulithOutboxPublisher JavaDoc).
    Boolean nullable =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'outbox'"
                + " AND column_name = 'signatures'",
            Boolean.class);
    assertThat(nullable).isTrue();
  }

  /**
   * Story 1.3 — the partial index that backs the unsigned-rows audit query (Story 10.1 LGTM
   * dashboard: {@code SELECT … WHERE signatures IS NULL}). Without the index the audit scan
   * degrades to a sequential scan once the outbox grows past the warm-up window.
   */
  @Test
  void v003OutboxUnsignedPartialIndexExists() {
    Integer partialIndexCount =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT COUNT(*) FROM pg_indexes"
                    + " WHERE schemaname = 'public' AND tablename = 'outbox'"
                    + " AND indexname = 'idx_outbox_unsigned'",
                Integer.class);
    assertThat(partialIndexCount)
        .as("V003 partial index idx_outbox_unsigned on outbox(id) WHERE signatures IS NULL")
        .isEqualTo(1);
  }

  /**
   * AC #9 — v1 is single-tenant. Every business row's {@code tenant_id} must be {@code 'default'}
   * after a fresh migration. A future migration that drops the DEFAULT or seeds a non-default
   * value silently breaks Story 5.x's multi-tenant activation; pin the invariant now.
   */
  @Test
  void v002TenantIdDefaultsToDefaultOnAllBusinessTables() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    for (String table : new String[] {"products", "variants", "attributes"}) {
      Long nonDefaultCount =
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE tenant_id <> 'default'", Long.class);
      assertThat(nonDefaultCount)
          .as("%s rows with non-default tenant_id (v1 must be single-tenant)", table)
          .isZero();
      String defaultExpr =
          jdbc.queryForObject(
              "SELECT column_default FROM information_schema.columns"
                  + " WHERE table_schema = 'public' AND table_name = ? AND column_name = 'tenant_id'",
              String.class,
              table);
      assertThat(defaultExpr)
          .as("%s.tenant_id column DEFAULT", table)
          .isEqualTo("'default'::character varying");
    }
  }
}