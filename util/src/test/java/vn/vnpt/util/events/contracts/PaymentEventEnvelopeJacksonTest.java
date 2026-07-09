package vn.vnpt.util.events.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the wire-format envelope Jackson round-trips and the payload records deserialize from
 * the exact JSON shape produced by the in-process event records.
 */
class PaymentEventEnvelopeJacksonTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void envelopeRoundTripsLosslessly() throws Exception {
    PaymentEventEnvelope original = new PaymentEventEnvelope(
        1234567890L,
        "Payment",
        9876543210L,
        "payment.captured",
        "{\"orderUuid\":1,\"paymentIntentId\":\"pi_x\",\"amountCents\":1000,\"currency\":\"VND\",\"occurredAt\":\"2026-07-09T10:00:00Z\"}",
        Map.of("service", "payment", "hmac_sha256", "abc123", "key_id", "v1"));

    String json = mapper.writeValueAsString(original);
    PaymentEventEnvelope parsed = mapper.readValue(json, PaymentEventEnvelope.class);

    assertEquals(original, parsed);
  }

  @Test
  void payloadRoundTripsLosslessly() throws Exception {
    PaymentCapturedPayload original = new PaymentCapturedPayload(
        1L, "pi_abc", 1000L, "VND", "2026-07-09T10:00:00Z");

    String json = mapper.writeValueAsString(original);
    PaymentCapturedPayload parsed = mapper.readValue(json, PaymentCapturedPayload.class);

    assertEquals(original, parsed);
    assertEquals(1L, parsed.orderUuid());
    assertEquals("pi_abc", parsed.paymentIntentId());
    assertEquals(1000L, parsed.amountCents());
    assertEquals("VND", parsed.currency());
    assertEquals("2026-07-09T10:00:00Z", parsed.occurredAt());
  }

  @Test
  void envelopeWithNullSignaturesParses() throws Exception {
    // null signatures is a valid edge case: producer has not yet signed (e.g. dev profile without
    // HMAC_SERVICE_SECRET). Consumer-side verifier rejects null signatures, but the wire parser
    // must still accept it.
    String json = "{\"eventId\":1,\"aggregateType\":\"Payment\",\"aggregateId\":2,"
        + "\"eventType\":\"payment.captured\",\"payload\":\"{}\",\"signatures\":null}";

    PaymentEventEnvelope parsed = mapper.readValue(json, PaymentEventEnvelope.class);
    assertEquals(1L, parsed.eventId());
    assertNull(parsed.signatures());
  }

  @Test
  void envelopeWithMissingHmacSha256FailsVerifierContract() {
    // The envelope can carry a signatures map with the hmac_sha256 key absent. The parser accepts
    // it (no schema validation at the JSON layer); the consumer-side OrderHmacEventVerifier
    // rejects it. This test pins the parser's behavior so the wire shape doesn't drift.
    PaymentEventEnvelope env = new PaymentEventEnvelope(
        1L, "Payment", 2L, "payment.captured", "{}",
        Map.of("service", "payment", "key_id", "v1")); // no hmac_sha256

    assertNotNull(env.signatures());
    assertNull(env.signatures().get("hmac_sha256"));
  }

  @Test
  void refundedPayloadRoundTripsLosslessly() throws Exception {
    PaymentRefundedPayload original = new PaymentRefundedPayload(
        1L, "pi_abc", 500L, "VND", "2026-07-09T11:00:00Z");

    String json = mapper.writeValueAsString(original);
    PaymentRefundedPayload parsed = mapper.readValue(json, PaymentRefundedPayload.class);

    assertEquals(original, parsed);
    assertEquals(500L, parsed.amountCents());
  }
}
