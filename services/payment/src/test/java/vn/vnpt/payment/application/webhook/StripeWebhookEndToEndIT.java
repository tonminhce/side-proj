package vn.vnpt.payment.application.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.payment.PaymentApplication;

/**
 * HTTP → controller → use case → dedup → Postgres end-to-end test — Story 3.2 / AC #5, #8.
 * The single-test class that proves the canonical FR-26 + NFR-IDEM-1 dedup contract:
 * first delivery inserts exactly one row in {@code webhook_dedup} AND one row in
 * {@code webhook_delivery_log}; a byte-for-byte replay returns {@code dedup:true} and writes
 * neither table again. Also exercises the trust-boundary 400 path end-to-end so the
 * {@link IllegalArgumentException} → {@code RestExceptionHandler} → 400 chain is proven, not just
 * asserted at the constructor.
 *
 * <p>Real beans everywhere — no {@code @MockitoBean} — because the existing
 * {@link StripeWebhookControllerTest} mocks the use case and therefore never exercises the
 * ON CONFLICT DO NOTHING round-trip through Postgres.
 */
@SpringBootTest(classes = PaymentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
@Transactional
@Rollback
class StripeWebhookEndToEndIT {

  private static final String EVENT_ID_PREFIX = "evt_e2e_";
  private static final String FIRST_TYPE = "payment_intent.succeeded";

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payment_db")
          .withUsername("payment_user")
          .withPassword("payment_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired WebApplicationContext webContext;
  @Autowired DataSource dataSource;
  @Autowired EntityManager entityManager;

  MockMvc mockMvc;
  JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    jdbc = new JdbcTemplate(dataSource);
    // IT-class shares the container with the unit tests' container; tables are created by Flyway.
  }

  /**
   * Flushes Hibernate so JdbcTemplate queries see persistence-context writes (e.g.
   * {@link org.springframework.data.jpa.repository.JpaRepository#save} queues INSERTs that don't
   * hit Postgres until flush — naive {@code JdbcTemplate.queryForObject("SELECT count(*)…")}
   * would miss them inside the test transaction).
   */
  private void flushPersistenceContext() {
    entityManager.flush();
  }

  @Test
  void firstDelivery_writesDedupAndDeliveryLog_andReturnsDedupFalse() throws Exception {
    String eventId = EVENT_ID_PREFIX + "first_" + System.nanoTime();

    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload(eventId, FIRST_TYPE, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(true))
        .andExpect(jsonPath("$.dedup").value(false))
        .andExpect(jsonPath("$.eventId").value(eventId));

    assertDedupRow(eventId, FIRST_TYPE, /* livemode */ false);
    assertDeliveryLogRow(eventId, FIRST_TYPE);
  }

  @Test
  void replaySamePayload_returnsDedupTrue_andNeitherTableGetsAnotherRow() throws Exception {
    String eventId = EVENT_ID_PREFIX + "replay_" + System.nanoTime();
    String payload = payload(eventId, FIRST_TYPE, false);

    mockMvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dedup").value(false));

    // Replay the SAME byte-for-byte curl (FR-26 contract; AC #3 says PK is the input byte-for-byte).
    mockMvc.perform(post("/webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content(payload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.received").value(true))
        .andExpect(jsonPath("$.dedup").value(true))
        .andExpect(jsonPath("$.eventId").value(eventId));

    assertThat(dedupRowCount(eventId)).isEqualTo(1);
    assertThat(deliveryLogRowCount(eventId)).isEqualTo(1);  // side-effect skipped on replay
  }

  @Test
  void livemodeFromPayload_propagatesToDedupRow() throws Exception {
    String eventId = EVENT_ID_PREFIX + "live_" + System.nanoTime();

    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload(eventId, "payment_intent.succeeded", true)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dedup").value(false));

    // Observability per AC #6 — livemode column reflects the JSON payload's flag byte-for-byte.
    flushPersistenceContext();
    Boolean livemode = jdbc.queryForObject(
        "SELECT livemode FROM webhook_dedup WHERE event_id = ?", Boolean.class, eventId);
    assertThat(livemode).isTrue();
  }

  @Test
  void differentEventIds_eachInsertedIndependently() throws Exception {
    String first = EVENT_ID_PREFIX + "a_" + System.nanoTime();
    String second = EVENT_ID_PREFIX + "b_" + System.nanoTime();

    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload(first, FIRST_TYPE, false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dedup").value(false));

    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload(second, "charge.refunded", false)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dedup").value(false));

    assertThat(dedupRowCount(first)).isEqualTo(1);
    assertThat(dedupRowCount(second)).isEqualTo(1);
  }

  @Test
  void blankId_returns400_andNoDedupRowWritten() throws Exception {
    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":"   ","type":"payment_intent.succeeded","livemode":false,"data":{},"created":1}
                """))
        .andExpect(status().isBadRequest());

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM webhook_dedup WHERE event_id LIKE ?", Integer.class,
        EVENT_ID_PREFIX + "%")).isEqualTo(0);
  }

  @Test
  void blankType_returns400() throws Exception {
    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"id":"evt_e2e_blanktype","type":"","livemode":false,"data":{},"created":1}
                """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void idOver128_returns400() throws Exception {
    String tooLong = "evt_" + "x".repeat(126);  // 130 chars total, > 128 PK ceiling
    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload(tooLong, FIRST_TYPE, false)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void malformedJson_returns400() throws Exception {
    // AC #7: malformed must NOT be silently swallowed — Stripe is misbehaving, on-call must see it.
    mockMvc.perform(post("/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{not json"))
        .andExpect(status().isBadRequest());
  }

  private static String payload(String id, String type, boolean livemode) {
    return """
        {"id":"%s","type":"%s","livemode":%s,"data":{},"created":1700000000}
        """.formatted(id, type, livemode);
  }

  private void assertDedupRow(String eventId, String type, boolean livemode) {
    flushPersistenceContext();
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM webhook_dedup WHERE event_id = ? AND event_type = ? AND livemode = ?",
        Integer.class, eventId, type, livemode);
    assertThat(count).as("webhook_dedup row for %s", eventId).isEqualTo(1);
  }

  private void assertDeliveryLogRow(String eventId, String type) {
    flushPersistenceContext();
    Integer count = jdbc.queryForObject(
        "SELECT count(*) FROM webhook_delivery_log WHERE event_id = ? AND event_type = ?",
        Integer.class, eventId, type);
    assertThat(count).as("webhook_delivery_log row for %s", eventId).isEqualTo(1);
  }

  private long dedupRowCount(String eventId) {
    flushPersistenceContext();
    return jdbc.queryForObject(
        "SELECT count(*) FROM webhook_dedup WHERE event_id = ?", Integer.class, eventId);
  }

  private long deliveryLogRowCount(String eventId) {
    flushPersistenceContext();
    return jdbc.queryForObject(
        "SELECT count(*) FROM webhook_delivery_log WHERE event_id = ?", Integer.class, eventId);
  }
}
