package vn.vnpt.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.vnpt.checkout.application.port.StripePaymentGateway;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.inventory.application.ReserveInventoryUseCase;
import vn.vnpt.inventory.domain.InventoryReservation;
import vn.vnpt.inventory.domain.ReservationStatus;

/**
 * End-to-end outbox tests for Story 2.3 (FR-19). Unlike {@code CheckoutControllerTest} (which mocks
 * the use cases), this boots the FULL wiring — real {@code StartCheckoutUseCase}, real
 * {@code CheckoutEventPublisher}, real {@code ModulithOutboxPublisher} — against a Testcontainers
 * Postgres, and asserts the {@code checkout.started} row actually lands in the {@code outbox} table
 * with the correct payload fields and a non-empty HMAC signature. Mirrors
 * {@code CartEventOutboxE2ETest} (Story 2.2).
 */
@SpringBootTest(classes = CheckoutApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class CheckoutEventOutboxE2ETest {

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

  @Autowired WebApplicationContext wac;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean StripePaymentGateway stripePaymentGateway;
  @MockitoBean ReserveInventoryUseCase reserveInventoryUseCase;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void stubStripe() {
    when(stripePaymentGateway.createPaymentIntent(anyLong(), anyString(), anyString()))
        .thenReturn(new StripePaymentGateway.Result("pi_e2e_test_abc", "pi_e2e_test_abc_secret"));
    // Stub inventory reserve so the saga's stock.reserve step succeeds — full path: CREATED →
    // STOCK_RESERVED → PAYMENT_PENDING. Without this the saga fails on INSUFFICIENT_STOCK.
    InventoryReservation fakeReservation = InventoryReservation.builder()
        .variantId(1001L)
        .warehouseId(7L)
        .quantity(2L)
        .status(ReservationStatus.ACTIVE)
        .tenantId("default")
        .sagaStepId("ignored")
        .orderUuid(0L)
        .expiresAt(Instant.now().plusSeconds(900))
        .build();
    when(reserveInventoryUseCase.reserve(any())).thenReturn(fakeReservation);
  }

  private MockMvc mvc() {
    return MockMvcBuilders.webAppContextSetup(wac).build();
  }

  /**
   * FR-19 — POST /api/checkouts/start through the real HTTP path; confirm a {@code checkout.started}
   * row lands in the outbox with the correct payload fields and a non-empty HMAC signature.
   */
  @Test
  void startCheckout_overHttp_emitsCheckoutStartedRowInOutbox() throws Exception {
    String body =
        "{\"cartUuid\":12345,\"userId\":\"u-abc-123\","
            + "\"shippingAddress\":{"
            + "\"recipientName\":\"Nguyen Van A\",\"phone\":\"0901234567\","
            + "\"addressLine1\":\"123 Le Loi\",\"city\":\"HCM\",\"province\":\"HCM\",\"country\":\"VN\"},"
            + "\"cartLines\":[{\"variantId\":1001,\"quantity\":2,\"unitPriceMinor\":50000}]}";

    String responseBody =
        mvc()
            .perform(
                post("/api/checkouts/start").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    @SuppressWarnings("unchecked")
    Map<String, Object> responseJson = objectMapper.readValue(responseBody, Map.class);
    long checkoutUuid = ((Number) responseJson.get("checkoutId")).longValue();

    Integer rows =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE event_type = 'checkout.started'"
                + " AND aggregate_id = ?",
            Integer.class,
            checkoutUuid);
    assertThat(rows).isEqualTo(1);

    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    // Postgres jsonb normalizes with a space after `:` and sorts keys; check the canonical fields
    // by extracting typed values from JSONB instead of substring-matching the serialized text.
    String checkoutUuidStr =
        jdbc.queryForObject(
            "SELECT payload->>'checkoutUuid' FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    assertThat(checkoutUuidStr).isEqualTo(String.valueOf(checkoutUuid));
    assertThat(payload).contains("\"cartUuid\"");
    assertThat(payload).contains("\"userId\"");
    assertThat(payload).contains("\"tenantId\"");
    assertThat(payload).contains("\"aggregateType\"");
    // Story 2.4 / FR-20 / AC #2 — the checkout.started event payload carries paymentIntentId so
    // downstream consumers (the saga in 2.5) can drive confirm/capture without a re-lookup.
    String paymentIntentId =
        jdbc.queryForObject(
            "SELECT payload->>'paymentIntentId' FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    assertThat(paymentIntentId).isEqualTo("pi_e2e_test_abc");

    String hmac =
        jdbc.queryForObject(
            "SELECT signatures->>'hmac_sha256' FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    assertThat(hmac).isNotBlank();

    // Story 2.5 / FR-22 — the saga runs in the same transaction as the producer, so after
    // commit the saga's 3 outbox rows + 3 transition-log rows are visible alongside the
    // checkout.started row.
    Integer orderCreatedRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'Order'"
            + " AND event_type = 'order.created' AND aggregate_id IN"
            + " (SELECT uuid FROM orders WHERE checkout_uuid = ?)",
        Integer.class,
        checkoutUuid);
    assertThat(orderCreatedRows).isEqualTo(1);
    Integer orderStockReservedRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'Order'"
            + " AND event_type = 'order.stock_reserved' AND aggregate_id IN"
            + " (SELECT uuid FROM orders WHERE checkout_uuid = ?)",
        Integer.class,
        checkoutUuid);
    assertThat(orderStockReservedRows).isEqualTo(1);
    Integer orderPaymentPendingRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'Order'"
            + " AND event_type = 'order.payment_pending' AND aggregate_id IN"
            + " (SELECT uuid FROM orders WHERE checkout_uuid = ?)",
        Integer.class,
        checkoutUuid);
    assertThat(orderPaymentPendingRows).isEqualTo(1);

    // 3 transition-log rows for the saga (cart.submit, stock.reserve, payment.intent.created).
    Integer transitionRows = jdbc.queryForObject(
        "SELECT COUNT(*) FROM order_state_transition"
            + " WHERE order_uuid IN (SELECT uuid FROM orders WHERE checkout_uuid = ?)",
        Integer.class,
        checkoutUuid);
    assertThat(transitionRows).isEqualTo(3);
  }

  /**
   * Guest-checkout path (FR-19 — B2C guest checkout) — POST with {@code guestCartId} only. The
   * outbox payload carries {@code guestCartId} and omits {@code userId} (NON_NULL).
   */
  @Test
  void startCheckout_guestCartId_overHttp_emitsCheckoutStartedRowInOutbox() throws Exception {
    String body =
        "{\"cartUuid\":54321,\"guestCartId\":\"guest-cookie-uuid\","
            + "\"shippingAddress\":{"
            + "\"recipientName\":\"Nguyen Van A\",\"phone\":\"0901234567\","
            + "\"addressLine1\":\"123 Le Loi\",\"city\":\"HCM\",\"province\":\"HCM\",\"country\":\"VN\"},"
            + "\"cartLines\":[{\"variantId\":2002,\"quantity\":1,\"unitPriceMinor\":75000}]}";

    String responseBody =
        mvc()
            .perform(
                post("/api/checkouts/start").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

    @SuppressWarnings("unchecked")
    Map<String, Object> responseJson = objectMapper.readValue(responseBody, Map.class);
    long checkoutUuid = ((Number) responseJson.get("checkoutId")).longValue();

    String guestCartId =
        jdbc.queryForObject(
            "SELECT payload->>'guestCartId' FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    assertThat(guestCartId).isEqualTo("guest-cookie-uuid");

    // userId was null on the wire — @JsonInclude(NON_NULL) strips it from the JSON payload.
    String userId =
        jdbc.queryForObject(
            "SELECT payload->>'userId' FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    assertThat(userId).isNull();
  }
}