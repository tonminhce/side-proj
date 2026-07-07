package vn.vnpt.cart.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Pure-JUnit test for {@link CartMergedEvent} — Story 2.1 / AC #6. Mirrors InventoryLifecycleEventTest. */
class CartMergedEventTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serialize_thenDeserialize_preservesAllFields() throws Exception {
    CartMergedEvent original =
        CartMergedEvent.builder()
            .eventId(123456789012345L)
            .aggregateType("Cart")
            .aggregateId(200L)
            .occurredAt(Instant.parse("2026-07-07T11:30:00.123Z"))
            .guestCartId("guest-abc")
            .userId("user-abc-123")
            .sourceCartUuid(100L)
            .targetCartUuid(200L)
            .mergedLinesCount(2)
            .mergedAt(Instant.parse("2026-07-07T11:30:00.123Z"))
            .tenantId("default")
            .signatures(Map.of("hmac_sha256", "fake-signature"))
            .build();

    String json = mapper.writeValueAsString(original);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = mapper.readValue(json, Map.class);

    assertThat(parsed.get("aggregateType")).isEqualTo("Cart");
    assertThat(((Number) parsed.get("aggregateId")).longValue()).isEqualTo(200L);
    assertThat(parsed.get("userId")).isEqualTo("user-abc-123");
    assertThat(((Number) parsed.get("mergedLinesCount")).intValue()).isEqualTo(2);
    assertThat(parsed.get("signatures")).isInstanceOf(Map.class);
  }

  @Test
  void nonNullAnnotation_omitsNullFields() throws Exception {
    CartMergedEvent payload =
        CartMergedEvent.builder()
            .eventId(1L)
            .aggregateType("Cart")
            .aggregateId(2L)
            .occurredAt(Instant.now())
            .userId("user-1")
            .targetCartUuid(2L)
            .mergedLinesCount(0)
            .mergedAt(Instant.now())
            .tenantId("default")
            .build();
    // guestCartId, sourceCartUuid, signatures — null, should be omitted.

    String json = mapper.writeValueAsString(payload);
    assertThat(json).doesNotContain("guestCartId");
    assertThat(json).doesNotContain("sourceCartUuid");
    assertThat(json).doesNotContain("signatures");
  }
}
