package vn.vnpt.checkout.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;

/** Story 2.3 / FR-19 — Jackson serialization + wire format. */
class CheckoutStartedEventTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void serialize_thenDeserialize_preservesAllFields() throws Exception {
    CheckoutStartedEvent original =
        CheckoutStartedEvent.builder()
            .eventId(98765L)
            .aggregateType("Checkout")
            .aggregateId(12345L)
            .occurredAt(Instant.parse("2026-07-07T10:15:30Z"))
            .checkoutUuid(12345L)
            .cartUuid(99L)
            .userId("u-abc-123")
            .tenantId("default")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van A")
                    .phone("0901234567")
                    .addressLine1("123 Le Loi")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .cartLines(List.of(CartLineSnapshot.builder().variantId(1001L).quantity(2).build()))
            .paymentIntentId("pi_xxx")
            .signatures(Map.of("hmac_sha256", "sig-value"))
            .build();

    String json = objectMapper.writeValueAsString(original);

    // Jackson 3 + @Value @Jacksonized records deserialize via Map<String, Object> round-trip
    // (no parameter-names constructor exposed) — same shape as cart's CartMergedEventTest.
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

    assertThat(((Number) parsed.get("eventId")).longValue()).isEqualTo(98765L);
    assertThat(parsed.get("aggregateType")).isEqualTo("Checkout");
    assertThat(((Number) parsed.get("checkoutUuid")).longValue()).isEqualTo(12345L);
    assertThat(((Number) parsed.get("cartUuid")).longValue()).isEqualTo(99L);
    assertThat(parsed.get("userId")).isEqualTo("u-abc-123");
    assertThat(parsed.get("tenantId")).isEqualTo("default");
    assertThat(parsed.get("paymentIntentId")).isEqualTo("pi_xxx");
    // client_secret is an R-15 secret and MUST NOT appear in the event payload — it flows only
    // via the HTTP response for the Stripe Elements iframe handoff.
    assertThat(parsed).doesNotContainKey("stripeClientSecret");
    @SuppressWarnings("unchecked")
    Map<String, Object> sigs = (Map<String, Object>) parsed.get("signatures");
    assertThat(sigs).containsEntry("hmac_sha256", "sig-value");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> lines = (List<Map<String, Object>>) parsed.get("cartLines");
    assertThat(lines).hasSize(1);
    assertThat(((Number) lines.get(0).get("variantId")).longValue()).isEqualTo(1001L);
    @SuppressWarnings("unchecked")
    Map<String, Object> shipping = (Map<String, Object>) parsed.get("shippingAddress");
    assertThat(shipping.get("city")).isEqualTo("HCM");
  }

  @Test
  void nonNullAnnotation_omitsNullFields() throws Exception {
    CheckoutStartedEvent event =
        CheckoutStartedEvent.builder()
            .eventId(1L)
            .aggregateType("Checkout")
            .aggregateId(12345L)
            .occurredAt(Instant.now())
            .checkoutUuid(12345L)
            .cartUuid(99L)
            .tenantId("default")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van A")
                    .phone("0901234567")
                    .addressLine1("123 Le Loi")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .cartLines(List.of()) // empty
            .build();

    String json = objectMapper.writeValueAsString(event);

    // userId, guestCartId, paymentIntentId, signatures all null → stripped from the wire.
    assertThat(json).doesNotContain("\"userId\"");
    assertThat(json).doesNotContain("\"guestCartId\"");
    assertThat(json).doesNotContain("\"paymentIntentId\"");
    assertThat(json).doesNotContain("\"signatures\"");
  }

  @Test
  void shippingAddress_serializesAsNestedObject_notFlattened() throws Exception {
    CheckoutStartedEvent event =
        CheckoutStartedEvent.builder()
            .eventId(1L)
            .aggregateType("Checkout")
            .aggregateId(12345L)
            .occurredAt(Instant.now())
            .checkoutUuid(12345L)
            .cartUuid(99L)
            .tenantId("default")
            .shippingAddress(
                ShippingAddress.builder()
                    .recipientName("Nguyen Van A")
                    .phone("0901234567")
                    .addressLine1("123 Le Loi")
                    .city("HCM")
                    .province("HCM")
                    .country("VN")
                    .build())
            .build();

    String json = objectMapper.writeValueAsString(event);

    // The wire format nests shippingAddress as an object — not flattened to top-level
    // recipientName / phone / etc.
    assertThat(json).contains("\"shippingAddress\":{");
    assertThat(json).contains("\"recipientName\":\"Nguyen Van A\"");
    assertThat(json).contains("\"addressLine1\":\"123 Le Loi\"");
  }
}