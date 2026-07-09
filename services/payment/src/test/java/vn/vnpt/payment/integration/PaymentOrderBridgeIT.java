package vn.vnpt.payment.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import vn.vnpt.order.OrderApplication;
import vn.vnpt.payment.PaymentApplication;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Cross-process integration test for the payment→order event bridge (Story 4.1 follow-up,
 * FR-32, ADR-14, ADR-20).
 *
 * <p>Boots both {@link PaymentApplication} and {@link OrderApplication} in the same JVM against
 * real Postgres + Kafka testcontainers, seeds a payment outbox row with a valid HMAC envelope,
 * and asserts the order service's saga listener advances the order to {@code PAID} via the
 * Kafka-driven bridge.
 *
 * <p>Profile: {@code bridge-it} is active; the bridge classes are gated by
 * {@code @Profile("!test")}, so {@code test} is intentionally NOT included (Spring's
 * {@code @Profile} match is OR — adding a positive profile does not re-enable
 * {@code @Profile("!test")} components when {@code test} is also active).
 */
@SpringBootTest(
    classes = {PaymentApplication.class, OrderApplication.class},
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("bridge-it")
@Testcontainers
class PaymentOrderBridgeIT {

  private static final String HMAC_SECRET = "integration-test-shared-secret-32+bytes!!";
  private static final long ORDER_UUID = 1L;
  private static final long AGGREGATE_ID = 42L;
  private static final long EVENT_ID = 1234567890123L;

  @Container
  static final PostgreSQLContainer<?> PAYMENT_DB =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("payment_db")
          .withUsername("payment_user")
          .withPassword("payment_pass");

  @Container
  static final PostgreSQLContainer<?> ORDER_DB =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("order_db")
          .withUsername("order_user")
          .withPassword("order_pass");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.0"));

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    // Payment DB
    registry.add("spring.datasource.url", PAYMENT_DB::getJdbcUrl);
    registry.add("spring.datasource.username", PAYMENT_DB::getUsername);
    registry.add("spring.datasource.password", PAYMENT_DB::getPassword);
    registry.add("POSTGRES_PAYMENT_DB", PAYMENT_DB::getDatabaseName);
    registry.add("POSTGRES_PAYMENT_USER", PAYMENT_DB::getUsername);
    registry.add("POSTGRES_PAYMENT_PASSWORD", PAYMENT_DB::getPassword);
    // Order DB
    registry.add("POSTGRES_ORDER_DB", ORDER_DB::getDatabaseName);
    registry.add("POSTGRES_ORDER_USER", ORDER_DB::getUsername);
    registry.add("POSTGRES_ORDER_PASSWORD", ORDER_DB::getPassword);
    // Kafka — both bridges read the same broker
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("payment.bridge.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("order.bridge.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    // HMAC shared secret (matches OrderHmacEventVerifier's DevHmacKeyProvider env var)
    registry.add("HMAC_SERVICE_SECRET_ORDER", () -> HMAC_SECRET);
    registry.add("HMAC_SERVICE_SECRET", () -> HMAC_SECRET);
    // Strip noisy autoconfigs that don't apply to this IT
    registry.add("spring.autoconfigure.exclude", () ->
        "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration");
  }

  @Test
  void paymentCapturedOutboxRowAdvancesOrderToPaid() throws Exception {
    // Step 1: seed order genesis + price snapshot in order_db so the saga can find the snapshot
    // when it tries to advance PLACED → PAID.
    seedOrderGenesis(ORDER_UUID);

    // Step 2: insert a valid HMAC-signed payment.captured outbox row in payment_db.
    String payload = String.format(
        "{\"orderUuid\":%d,\"paymentIntentId\":\"pi_x\",\"amountCents\":1000,"
            + "\"currency\":\"VND\",\"occurredAt\":\"%s\"}",
        ORDER_UUID, LocalDateTime.now(ZoneOffset.UTC));
    long outboxId = insertOutboxRow(payload);

    // Step 3: await the saga — the bridge poller picks up the row, sends to Kafka, the order
    // consumer verifies HMAC, re-publishes SignedPaymentCapturedEvent, the advancer appends PAID.
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> {
          String state = readOrderState(ORDER_UUID);
          assertNotNull(state, "expected order_state_transition row to exist");
          assertEquals("PAID", state, "saga did not advance to PAID");
        });

    // Step 4: bridge marked the outbox row as published.
    assertNotNull(readPublishedAt(outboxId), "outbox row was not marked published_at");
  }

  // ---- helpers ----

  private void seedOrderGenesis(long orderUuid) throws SQLException {
    try (Connection c = DriverManager.getConnection(
        ORDER_DB.getJdbcUrl(), ORDER_DB.getUsername(), ORDER_DB.getPassword())) {
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO order_state_transition"
              + " (order_uuid, from_state, to_state, saga_step, event_id, created_at)"
              + " VALUES (?, NULL, 'PLACED', 'order.placed', ?, now())")) {
        ps.setLong(1, orderUuid);
        ps.setLong(2, EVENT_ID);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO order_price_snapshot"
              + " (order_uuid, list_price_cents, tax_cents, shipping_cents, total_cents,"
              + "  currency, captured_at)"
              + " VALUES (?, 1000, 0, 0, 1000, 'VND', now())")) {
        ps.setLong(1, orderUuid);
        ps.executeUpdate();
      }
    }
  }

  private long insertOutboxRow(String payload) throws SQLException {
    String canonical = JcsCanonicalJson.serialize(Map.of(
        "event_id", EVENT_ID,
        "event_type", "payment.captured",
        "aggregate_type", "Payment",
        "aggregate_id", AGGREGATE_ID,
        "payload", payload));
    String sig = HmacEventSigner.sign(canonical, HMAC_SECRET);
    String signatures = String.format(
        "{\"service\":\"payment\",\"hmac_sha256\":\"%s\",\"key_id\":\"v1\"}", sig);

    try (Connection c = DriverManager.getConnection(
        PAYMENT_DB.getJdbcUrl(), PAYMENT_DB.getUsername(), PAYMENT_DB.getPassword())) {
      try (PreparedStatement ps = c.prepareStatement(
          "INSERT INTO outbox"
              + " (aggregate_type, aggregate_id, event_type, event_id, payload, signatures, created_at)"
              + " VALUES ('Payment', ?, 'payment.captured', ?, ?::jsonb, ?::jsonb, now())",
          new String[] {"id"})) {
        ps.setLong(1, AGGREGATE_ID);
        ps.setLong(2, EVENT_ID);
        ps.setString(3, payload);
        ps.setString(4, signatures);
        ps.executeUpdate();
        try (ResultSet rs = ps.getGeneratedKeys()) {
          assertTrue(rs.next());
          return rs.getLong(1);
        }
      }
    }
  }

  private String readOrderState(long orderUuid) throws SQLException {
    try (Connection c = DriverManager.getConnection(
        ORDER_DB.getJdbcUrl(), ORDER_DB.getUsername(), ORDER_DB.getPassword());
        PreparedStatement ps = c.prepareStatement(
            "SELECT to_state FROM order_state_transition"
                + " WHERE order_uuid = ? ORDER BY id DESC LIMIT 1")) {
      ps.setLong(1, orderUuid);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  private java.time.Instant readPublishedAt(long outboxId) throws SQLException {
    try (Connection c = DriverManager.getConnection(
        PAYMENT_DB.getJdbcUrl(), PAYMENT_DB.getUsername(), PAYMENT_DB.getPassword());
        PreparedStatement ps = c.prepareStatement(
            "SELECT published_at FROM outbox WHERE id = ?")) {
      ps.setLong(1, outboxId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next() || rs.getTimestamp(1) == null) {
          return null;
        }
        return rs.getTimestamp(1).toInstant();
      }
    }
  }
}
