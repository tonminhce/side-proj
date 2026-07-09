package vn.vnpt.payment.infrastructure.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the poller reads outbox rows, serializes them as {@link
 * vn.vnpt.util.events.contracts.PaymentEventEnvelope}, sends to Kafka, and marks the row published.
 * Uses Mockito for the JdbcTemplate + KafkaProducer; the bridge's DataSource → JdbcTemplate
 * constructor is bypassed by injecting a JdbcTemplate directly via reflection-friendly subclassing.
 *
 * <p>// ponytail: DataSource is required by the constructor; we use Mockito.mock(DataSource.class)
 * because the bridge calls {@code new JdbcTemplate(dataSource)} once at construction, then uses
 * the JdbcTemplate directly. A full Spring context would over-mock.
 */
class PaymentEventKafkaBridgeTest {

  private DataSource dataSource;
  private JdbcTemplate jdbcTemplate;
  @SuppressWarnings("unchecked")
  private final KafkaProducer<String, String> producer = mock(KafkaProducer.class);
  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final PaymentBridgeKafkaProperties props = new PaymentBridgeKafkaProperties();
  private PaymentEventKafkaBridge bridge;

  @BeforeEach
  void setUp() {
    dataSource = mock(DataSource.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    bridge = new PaymentEventKafkaBridge(dataSource, producer, mapper, props) {
      // Re-route the super-constructor's "new JdbcTemplate(dataSource)" through the mocked one.
      // The base ctor assigns its own JdbcTemplate; we override the public methods we care about
      // by routing them to the mocked jdbcTemplate via a small adapter class below.
    };
    // Replace the bridge's internal JdbcTemplate via reflection (it's a private final field).
    try {
      var field = PaymentEventKafkaBridge.class.getDeclaredField("jdbcTemplate");
      field.setAccessible(true);
      field.set(bridge, jdbcTemplate);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void emptyOutboxNoOp() {
    when(jdbcTemplate.queryForList(any(String.class), any(Integer.class))).thenReturn(List.of());

    bridge.poll();

    verify(producer, never()).send(any());
    verify(jdbcTemplate, never()).update(any(String.class), any(Object[].class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void singleRowSendsAndMarksPublished() throws Exception {
    Map<String, Object> raw = new HashMap<>();
    raw.put("id", 1L);
    raw.put("aggregate_type", "Payment");
    raw.put("aggregate_id", 42L);
    raw.put("event_type", "payment.captured");
    raw.put("event_id", 1000L);
    raw.put("payload", "{\"orderUuid\":1,\"paymentIntentId\":\"pi_x\"}");
    raw.put("signatures", "{\"service\":\"payment\",\"hmac_sha256\":\"abc\",\"key_id\":\"v1\"}");
    when(jdbcTemplate.queryForList(any(String.class), any(Integer.class)))
        .thenReturn(List.of(raw));

    TopicPartition tp = new TopicPartition("payment.events", 0);
    RecordMetadata meta = new RecordMetadata(tp, 0, 0, 0L, 0, 0);
    when(producer.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(meta));

    bridge.poll();

    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(producer, times(1)).send(captor.capture());
    ProducerRecord<String, String> sent = captor.getValue();
    assertEquals("payment.events", sent.topic());
    assertEquals("Payment:42", sent.key());
    assertTrue(sent.value().contains("\"eventId\":1000"));
    assertTrue(sent.value().contains("\"hmac_sha256\":\"abc\""));
    verify(jdbcTemplate, times(1)).update(any(String.class), any(Object.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void nullSignaturesJsonStillSendsWithEmptyMap() throws Exception {
    Map<String, Object> raw = new HashMap<>();
    raw.put("id", 2L);
    raw.put("aggregate_type", "Payment");
    raw.put("aggregate_id", 7L);
    raw.put("event_type", "payment.captured");
    raw.put("event_id", 2000L);
    raw.put("payload", "{}");
    raw.put("signatures", null);
    when(jdbcTemplate.queryForList(any(String.class), any(Integer.class)))
        .thenReturn(List.of(raw));

    TopicPartition tp = new TopicPartition("payment.events", 0);
    RecordMetadata meta = new RecordMetadata(tp, 0, 0, 0L, 0, 0);
    when(producer.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(meta));

    bridge.poll();

    ArgumentCaptor<ProducerRecord<String, String>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(producer).send(captor.capture());
    assertTrue(captor.getValue().value().contains("\"signatures\":{}"));
  }
}
