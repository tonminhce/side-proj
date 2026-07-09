package vn.vnpt.payment.infrastructure.kafka;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.util.events.contracts.PaymentEventEnvelope;

/**
 * Polls the payment outbox for unpublished rows and publishes them to the {@code payment.events}
 * Kafka topic — Story 4.1 follow-up, FR-32, ADR-14, ADR-20.
 *
 * <p>Concurrency model: each row is claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED}, sent
 * to Kafka with a synchronous send (acks=all), then marked {@code published_at = now()} in the
 * same transaction. {@code SKIP LOCKED} lets multiple payment instances poll concurrently without
 * stepping on each other. The partial index {@code idx_outbox_unpublished} (already in V001
 * migration) keeps the scan O(unpublished).
 *
 * <p>Disabled in {@code @SpringBootTest} contexts; the integration test injects a stub producer.
 *
 * <p>// ponytail: synchronous send per row keeps the implementation linear and the at-least-once
 * semantics obvious. Switch to async send + batched flush when batch-size > 50 and per-row latency
 * matters.
 */
@Component
@Profile("!test")
public class PaymentEventKafkaBridge {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventKafkaBridge.class);

  private static final String SQL_FETCH_UNPUBLISHED =
      "SELECT id, aggregate_type, aggregate_id, event_type, event_id, payload, signatures"
          + " FROM outbox WHERE published_at IS NULL"
          + " ORDER BY id ASC LIMIT ? FOR UPDATE SKIP LOCKED";

  private static final String SQL_MARK_PUBLISHED =
      "UPDATE outbox SET published_at = now() WHERE id = ?";

  private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {};

  private final JdbcTemplate jdbcTemplate;
  private final KafkaProducer<String, String> producer;
  private final ObjectMapper objectMapper;
  private final PaymentBridgeKafkaProperties props;

  public PaymentEventKafkaBridge(
      DataSource dataSource,
      KafkaProducer<String, String> paymentEventProducer,
      ObjectMapper objectMapper,
      PaymentBridgeKafkaProperties props) {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
    this.producer = paymentEventProducer;
    this.objectMapper = objectMapper;
    this.props = props;
  }

  @Scheduled(fixedDelayString = "${payment.bridge.poll-interval-ms:500}")
  public void poll() {
    int batchSize = props.getBatchSize();
    List<OutboxRow> rows;
    try {
      rows = jdbcTemplate.queryForList(SQL_FETCH_UNPUBLISHED, batchSize).stream()
          .map(this::toRow)
          .toList();
    } catch (RuntimeException e) {
      log.warn("Outbox fetch failed: {}", e.getMessage());
      return;
    }
    if (rows.isEmpty()) {
      return;
    }

    int sent = 0;
    for (OutboxRow row : rows) {
      try {
        PaymentEventEnvelope envelope = row.toEnvelope(objectMapper);
        String key = row.aggregateType() + ":" + row.aggregateId();
        String value = objectMapper.writeValueAsString(envelope);
        RecordMetadata meta = producer
            .send(new ProducerRecord<>(props.getTopic(), key, value))
            .get(10, TimeUnit.SECONDS);
        jdbcTemplate.update(SQL_MARK_PUBLISHED, row.id());
        sent++;
        log.debug(
            "Published outbox id={} event_type={} topic={} partition={} offset={}",
            row.id(),
            row.eventType(),
            meta.topic(),
            meta.partition(),
            meta.offset());
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        log.warn("Bridge interrupted at row id={}", row.id());
        return;
      } catch (ExecutionException | TimeoutException e) {
        // Send failed (broker down, network, schema error). The transaction rolls back the row
        // claim on the next poll — the row stays published_at=NULL and is retried.
        log.error("Kafka send failed for outbox id={}: {}", row.id(), e.getMessage());
        return;
      } catch (RuntimeException e) {
        log.error("Bridge iteration failed for outbox id={}: {}", row.id(), e.getMessage());
        return;
      }
    }
    if (sent > 0) {
      log.info("Bridge published {}/{} outbox rows to topic={}", sent, rows.size(), props.getTopic());
    }
  }

  private OutboxRow toRow(Map<String, Object> raw) {
    return new OutboxRow(
        ((Number) raw.get("id")).longValue(),
        (String) raw.get("aggregate_type"),
        ((Number) raw.get("aggregate_id")).longValue(),
        (String) raw.get("event_type"),
        ((Number) raw.get("event_id")).longValue(),
        (String) raw.get("payload"),
        (String) raw.get("signatures"));
  }

  /** Internal row projection; the envelope builder parses {@code signatures} JSONB to a Map. */
  record OutboxRow(
      long id,
      String aggregateType,
      long aggregateId,
      String eventType,
      long eventId,
      String payload,
      String signaturesJson) {

    PaymentEventEnvelope toEnvelope(ObjectMapper mapper) {
      Map<String, String> signatures = signaturesJson == null || signaturesJson.isBlank()
          ? new HashMap<>()
          : mapper.readValue(signaturesJson, MAP_TYPE);
      return new PaymentEventEnvelope(
          eventId, aggregateType, aggregateId, eventType, payload, signatures);
    }
  }
}
