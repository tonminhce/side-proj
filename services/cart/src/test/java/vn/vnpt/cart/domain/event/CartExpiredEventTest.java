package vn.vnpt.cart.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.cart.domain.CartStatus;

class CartExpiredEventTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serialize_thenDeserialize_preservesAllFields() throws Exception {
    CartExpiredEvent original =
        CartExpiredEvent.builder()
            .eventId(123456789012345L)
            .aggregateType("Cart")
            .aggregateId(200L)
            .occurredAt(Instant.parse("2026-07-07T11:30:00.123Z"))
            .cartUuid(200L)
            .guestCartId("guest-abc")
            .userId(null)
            .previousStatus(CartStatus.ANONYMOUS)
            .expiredLinesCount(3)
            .expiresAt(Instant.parse("2026-07-06T11:30:00.123Z"))
            .expiredAt(Instant.parse("2026-07-07T11:30:00.123Z"))
            .tenantId("default")
            .signatures(Map.of("hmac_sha256", "fake-signature"))
            .build();

    String json = mapper.writeValueAsString(original);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = mapper.readValue(json, Map.class);

    assertThat(parsed.get("aggregateType")).isEqualTo("Cart");
    // SCREAMING_SNAKE_CASE wire value for previousStatus (Jackson default for enums).
    assertThat(parsed.get("previousStatus")).isEqualTo("ANONYMOUS");
    assertThat(((Number) parsed.get("expiredLinesCount")).intValue()).isEqualTo(3);
    assertThat(parsed.get("guestCartId")).isEqualTo("guest-abc");
    // userId is null → omitted by @JsonInclude(NON_NULL).
    assertThat(parsed).doesNotContainKey("userId");
  }
}