package vn.vnpt.order.infrastructure.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.vnpt.order.application.saga.event.PaymentCapturedEvent;
import vn.vnpt.order.application.saga.event.SignedPaymentCapturedEvent;
import vn.vnpt.order.application.saga.event.SignedPaymentRefundedEvent;
import vn.vnpt.order.infrastructure.security.HmacServiceKeyProvider;
import vn.vnpt.order.infrastructure.security.OrderHmacEventVerifier;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;
import vn.vnpt.util.events.contracts.PaymentEventEnvelope;

/**
 * Verifies the consumer deserializes the envelope, verifies the HMAC, and re-publishes the
 * in-process {@code SignedPaymentCapturedEvent} / {@code SignedPaymentRefundedEvent}. Uses
 * Mockito to stub the consumer + publisher; the verifier is real and uses a known test secret.
 */
class PaymentEventKafkaListenerTest {

  private static final String TEST_SECRET = "test-secret-for-listener-tests";
  private static final long FIXED_EVENT_ID = 1234567890123L;
  private static final long FIXED_AGGREGATE_ID = 9876543210L;

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final OrderBridgeKafkaProperties props = new OrderBridgeKafkaProperties();
  private final HmacServiceKeyProvider keyProvider = () -> TEST_SECRET;
  private final OrderHmacEventVerifier verifier = new OrderHmacEventVerifier(keyProvider);
  private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private PaymentEventKafkaListener listener;

  @BeforeEach
  void setUp() {
    // The listener ctor takes a KafkaConsumer; we never call poll() in unit tests, so a null
    // stub is fine (the daemon thread is not started because we never invoke @PostConstruct).
    listener = new PaymentEventKafkaListener(
        null, mapper, verifier, publisher, props, meterRegistry);
  }

  @Test
  void validCapturedEnvelopePublishesSignedEvent() throws Exception {
    PaymentEventEnvelope env = buildSignedEnvelope("payment.captured", buildCapturedPayload());
    String json = mapper.writeValueAsString(env);

    listener.processRecord(new ConsumerRecord<>("payment.events", 0, 0L, "Payment:1", json));

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(publisher, times(1)).publishEvent(captor.capture());
    Object published = captor.getValue();
    assertTrue(published instanceof SignedPaymentCapturedEvent);
    SignedPaymentCapturedEvent signed = (SignedPaymentCapturedEvent) published;
    assertEquals(FIXED_EVENT_ID, signed.eventId());
    assertEquals("Payment", signed.aggregateType());
    assertEquals(FIXED_AGGREGATE_ID, signed.aggregateId());
    assertNotNull(signed.payloadJson());
    PaymentCapturedEvent payload = signed.payload();
    assertEquals(1L, payload.orderUuid());
    assertEquals("pi_abc", payload.paymentIntentId());
    assertEquals(1000L, payload.amountCents());
    assertEquals("VND", payload.currency());
  }

  @Test
  void validRefundedEnvelopePublishesSignedRefundedEvent() throws Exception {
    PaymentEventEnvelope env = buildSignedEnvelope("payment.refunded",
        "{\"orderUuid\":1,\"paymentIntentId\":\"pi_abc\",\"amountCents\":500,\"currency\":\"VND\",\"occurredAt\":\"2026-07-09T11:00:00Z\"}");
    String json = mapper.writeValueAsString(env);

    listener.processRecord(new ConsumerRecord<>("payment.events", 0, 1L, "Payment:1", json));

    ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
    verify(publisher, times(1)).publishEvent(captor.capture());
    assertTrue(captor.getValue() instanceof SignedPaymentRefundedEvent);
  }

  @Test
  void badSignatureIncrementsMismatchCounterAndDoesNotPublish() throws Exception {
    PaymentEventEnvelope env = new PaymentEventEnvelope(
        FIXED_EVENT_ID, "Payment", FIXED_AGGREGATE_ID, "payment.captured",
        buildCapturedPayload(),
        Map.of("service", "payment", "hmac_sha256", "invalid-signature", "key_id", "v1"));
    String json = mapper.writeValueAsString(env);

    boolean ok = listener.processRecord(
        new ConsumerRecord<>("payment.events", 0, 2L, "Payment:1", json));

    // processRecord returns false (caller will commit); the bad message is NOT republished.
    assertEquals(false, ok);
    verify(publisher, never()).publishEvent(any());
    assertEquals(1.0, meterRegistry.counter(
        "security.event.signature.mismatch", "producer", "payment", "source", "kafka").count());
  }

  @Test
  void unknownEventTypeCommitsAndSkips() throws Exception {
    PaymentEventEnvelope env = buildSignedEnvelope("payment.unknown", "{}");
    String json = mapper.writeValueAsString(env);

    boolean ok = listener.processRecord(
        new ConsumerRecord<>("payment.events", 0, 3L, "Payment:1", json));

    assertEquals(true, ok);
    verify(publisher, never()).publishEvent(any());
  }

  @Test
  void malformedJsonDoesNotThrow() {
    boolean ok = listener.processRecord(
        new ConsumerRecord<>("payment.events", 0, 4L, "Payment:1", "{not json}"));

    assertEquals(false, ok);
    verify(publisher, never()).publishEvent(any());
    assertEquals(1.0, meterRegistry.counter("order.bridge.event.error").count());
  }

  private String buildCapturedPayload() {
    return "{\"orderUuid\":1,\"paymentIntentId\":\"pi_abc\",\"amountCents\":1000,"
        + "\"currency\":\"VND\",\"occurredAt\":\"2026-07-09T10:00:00Z\"}";
  }

  private PaymentEventEnvelope buildSignedEnvelope(String eventType, String payload) {
    Map<String, Object> env = Map.of(
        "event_id", FIXED_EVENT_ID,
        "event_type", eventType,
        "aggregate_type", "Payment",
        "aggregate_id", FIXED_AGGREGATE_ID,
        "payload", payload);
    String canonical = JcsCanonicalJson.serialize(env);
    String sig = HmacEventSigner.sign(canonical, TEST_SECRET);
    return new PaymentEventEnvelope(
        FIXED_EVENT_ID, "Payment", FIXED_AGGREGATE_ID, eventType, payload,
        Map.of("service", "payment", "hmac_sha256", sig, "key_id", "v1"));
  }
}
