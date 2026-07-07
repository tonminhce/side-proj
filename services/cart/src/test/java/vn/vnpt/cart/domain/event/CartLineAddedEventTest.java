package vn.vnpt.cart.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CartLineAddedEventTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serialize_thenDeserialize_preservesAllFields() throws Exception {
    CartLineAddedEvent original =
        CartLineAddedEvent.builder()
            .eventId(123456789012345L)
            .aggregateType("Cart")
            .aggregateId(200L)
            .occurredAt(Instant.parse("2026-07-07T11:30:00.123Z"))
            .cartUuid(200L)
            .lineUuid(300L)
            .variantId(1001L)
            .quantity(2)
            .tenantId("default")
            .signatures(Map.of("hmac_sha256", "fake-signature"))
            .build();

    String json = mapper.writeValueAsString(original);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = mapper.readValue(json, Map.class);

    assertThat(parsed.get("aggregateType")).isEqualTo("Cart");
    assertThat(((Number) parsed.get("aggregateId")).longValue()).isEqualTo(200L);
    assertThat(((Number) parsed.get("cartUuid")).longValue()).isEqualTo(200L);
    assertThat(((Number) parsed.get("variantId")).longValue()).isEqualTo(1001L);
    assertThat(((Number) parsed.get("quantity")).intValue()).isEqualTo(2);
    assertThat(parsed.get("signatures")).isInstanceOf(Map.class);
  }

  @Test
  void nonNullAnnotation_omitsNullFields() throws Exception {
    CartLineAddedEvent payload =
        CartLineAddedEvent.builder()
            .eventId(1L)
            .aggregateType("Cart")
            .aggregateId(2L)
            .occurredAt(Instant.now())
            .cartUuid(2L)
            .lineUuid(3L)
            .variantId(1001L)
            .quantity(1)
            .tenantId("default")
            .build();
    // signatures is null — must be omitted on the wire.

    String json = mapper.writeValueAsString(payload);
    assertThat(json).doesNotContain("signatures");
  }
}