package vn.vnpt.inventory.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure-JUnit test for {@link InventoryLifecycleEvent} — Story 1.8 / FR-11.
 *
 * <p>Round-trips the record through Jackson (the outbox serializer) and asserts the
 * {@code phase} field serializes correctly. {@code @JsonInclude(NON_NULL)} ensures
 * phase-specific nullable fields are OMITTED when null.
 */
class InventoryLifecycleEventTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serialize_thenDeserialize_preservesPhaseField() throws Exception {
    long eventId = 123456789012345L;
    long reservationUuid = 987654321098765L;
    InventoryLifecycleEvent original =
        InventoryLifecycleEvent.builder()
            .eventId(eventId)
            .aggregateType("InventoryReservation")
            .aggregateId(reservationUuid)
            .occurredAt(Instant.parse("2026-07-07T11:30:00.123Z"))
            .phase(LifecyclePhase.RESERVED)
            .reservationUuid(reservationUuid)
            .variantId(555L)
            .warehouseId(1001L)
            .quantity(3L)
            .reason("reserve")
            .sagaStepId("checkout-abc-123-payment.reserve")
            .tenantId("default")
            .signatures(Map.of("hmac_sha256", "fake-signature"))
            .build();

    String json = mapper.writeValueAsString(original);
    assertThat(json).contains("\"phase\":\"RESERVED\"");

    // Parse back into a generic Map to validate null-omission behavior
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = mapper.readValue(json, Map.class);
    assertThat(parsed.get("phase")).isEqualTo("RESERVED");
    assertThat(((Number) parsed.get("eventId")).longValue()).isEqualTo(eventId);
    assertThat(parsed.get("signatures")).isInstanceOf(Map.class);
  }

  @Test
  void nonNullAnnotation_omitsNullFields() throws Exception {
    InventoryLifecycleEvent payload =
        InventoryLifecycleEvent.builder()
            .eventId(1L)
            .aggregateType("InventoryLedger")
            .aggregateId(2L)
            .occurredAt(Instant.now())
            .phase(LifecyclePhase.ADJUSTED)
            .variantId(100L)
            .warehouseId(200L)
            .quantity(-1L)
            .reason("adjust")
            .tenantId("default")
            .build();
    // reservationUuid, sagaStepId, orderUuid, signatures — all null, should be omitted.

    String json = mapper.writeValueAsString(payload);
    assertThat(json).doesNotContain("reservationUuid");
    assertThat(json).doesNotContain("sagaStepId");
    assertThat(json).doesNotContain("orderUuid");
    assertThat(json).doesNotContain("signatures");
  }
}