package vn.vnpt.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

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

  private final ObjectMapper objectMapper = new ObjectMapper();

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
            + "\"cartLines\":[{\"variantId\":1001,\"quantity\":2}],"
            + "\"stripeClientSecret\":\"pi_xxx_secret_xxx\"}";

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

    String hmac =
        jdbc.queryForObject(
            "SELECT signatures->>'hmac_sha256' FROM outbox"
                + " WHERE event_type = 'checkout.started' AND aggregate_id = ?",
            String.class,
            checkoutUuid);
    assertThat(hmac).isNotBlank();
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
            + "\"cartLines\":[{\"variantId\":2002,\"quantity\":1}]}";

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