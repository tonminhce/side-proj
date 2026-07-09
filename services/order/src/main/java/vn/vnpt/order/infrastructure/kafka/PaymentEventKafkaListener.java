package vn.vnpt.order.infrastructure.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.order.application.saga.event.PaymentCapturedEvent;
import vn.vnpt.order.application.saga.event.PaymentRefundedEvent;
import vn.vnpt.order.application.saga.event.SignedPaymentCapturedEvent;
import vn.vnpt.order.application.saga.event.SignedPaymentRefundedEvent;
import vn.vnpt.order.infrastructure.security.OrderHmacEventVerifier;
import vn.vnpt.util.events.contracts.PaymentCapturedPayload;
import vn.vnpt.util.events.contracts.PaymentEventEnvelope;
import vn.vnpt.util.events.contracts.PaymentRefundedPayload;

/**
 * Listens to the {@code payment.events} Kafka topic, verifies the HMAC envelope, and re-publishes
 * a {@code SignedPayment*Event} to the in-process bus so the existing saga listener
 * ({@code PaymentCapturedOrderAdvancer} / {@code PaymentRefundedOrderAdvancer}) runs unchanged.
 *
 * <p>Verification: {@link OrderHmacEventVerifier#verifyPaymentEventEnvelope} rejects envelopes
 * whose {@code signatures.service != "payment"}, whose {@code hmac_sha256} is missing, or whose
 * recomputed HMAC does not match. On rejection, the
 * {@code security.event.signature.mismatch{source=kafka}} counter increments and the offset is
 * still committed (the message is permanently bad — replaying won't change the signature).
 *
 * <p>Disabled in {@code @SpringBootTest} contexts; tests construct the listener directly and
 * invoke {@link #processRecord(ConsumerRecord)} without a consumer poll loop.
 *
 * <p>// ponytail: single daemon thread, manual offset commit, at-least-once with idempotent
 * downstream (AppendOrderTransitionUseCase checks state-machine validity). Switch to a
 * thread-pool consumer when partition count > 4 and a single thread is saturated.
 */
@Component
@Profile("!test")
public class PaymentEventKafkaListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventKafkaListener.class);

  private final KafkaConsumer<String, String> consumer;
  private final ObjectMapper objectMapper;
  private final OrderHmacEventVerifier verifier;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final OrderBridgeKafkaProperties props;
  private final Counter signatureMismatchCounter;
  private final Counter processedCounter;
  private final Counter errorCounter;

  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread pollThread;

  public PaymentEventKafkaListener(
      KafkaConsumer<String, String> paymentEventConsumer,
      ObjectMapper objectMapper,
      OrderHmacEventVerifier verifier,
      ApplicationEventPublisher applicationEventPublisher,
      OrderBridgeKafkaProperties props,
      MeterRegistry meterRegistry) {
    this.consumer = paymentEventConsumer;
    this.objectMapper = objectMapper;
    this.verifier = verifier;
    this.applicationEventPublisher = applicationEventPublisher;
    this.props = props;
    this.signatureMismatchCounter = Counter.builder("security.event.signature.mismatch")
        .tag("producer", "payment")
        .tag("source", "kafka")
        .register(meterRegistry);
    this.processedCounter = Counter.builder("order.bridge.event.processed")
        .register(meterRegistry);
    this.errorCounter = Counter.builder("order.bridge.event.error")
        .register(meterRegistry);
  }

  @PostConstruct
  public void start() {
    running.set(true);
    pollThread = new Thread(this::pollLoop, "order-payment-bridge");
    pollThread.setDaemon(true);
    pollThread.start();
    log.info("Payment bridge listener started: topic={} group={}",
        props.getTopic(), props.getGroupId());
  }

  @PreDestroy
  public void stop() {
    running.set(false);
    if (pollThread != null) {
      pollThread.interrupt();
    }
  }

  private void pollLoop() {
    while (running.get()) {
      try {
        ConsumerRecords<String, String> records = consumer.poll(java.time.Duration.ofMillis(props.getPollTimeoutMs()));
        if (records.isEmpty()) {
          continue;
        }
        // Track per-batch failure so a transient dispatch error skips the commit (the failing
        // record will be redelivered on the next poll). C2 (closed 2026-07-09) used to swallow
        // these inside dispatch*; the swallow was moved up here, with a per-record guard that
        // DOES commit (bad JSON / bad envelope) but does NOT commit on a transient dispatch
        // throw (e.g. downstream listener's @Transactional rollback).
        boolean allCommitted = true;
        for (ConsumerRecord<String, String> record : records) {
          try {
            processRecord(record);
          } catch (RuntimeException e) {
            // Transient: do not commit this batch. The bad record will replay and (per
            // AppendOrderTransitionUseCase's V005 dedupe + the snapshot race fix) either succeed
            // or be a known-good no-op.
            allCommitted = false;
            errorCounter.increment();
            log.error("processRecord failed at offset={} partition={} — NOT committing batch: {}",
                record.offset(), record.partition(), e.getMessage());
          }
        }
        if (allCommitted) {
          consumer.commitSync();
        }
      } catch (org.apache.kafka.common.errors.WakeupException w) {
        log.info("Bridge consumer wakeup — shutting down");
        return;
      } catch (RuntimeException e) {
        log.warn("Bridge poll loop iteration failed: {}", e.getMessage());
      }
    }
  }

  /**
   * Process a single Kafka record. Package-private so tests can drive it without a consumer.
   * Returns true on success (committed), false on signature mismatch (also committed — message is
   * permanently bad), throws on transient failure (caller should not commit).
   */
  boolean processRecord(ConsumerRecord<String, String> record) {
    PaymentEventEnvelope envelope;
    try {
      envelope = objectMapper.readValue(record.value(), PaymentEventEnvelope.class);
    } catch (Exception e) {
      errorCounter.increment();
      log.error("Malformed envelope at offset={} partition={}: {}",
          record.offset(), record.partition(), e.getMessage());
      return false; // commit and move on — malformed payloads won't fix themselves
    }

    boolean valid = verifier.verifyPaymentEventEnvelope(
        envelope.eventId(),
        envelope.eventType(),
        envelope.aggregateType(),
        envelope.aggregateId(),
        envelope.payload(),
        envelope.signatures());
    if (!valid) {
      signatureMismatchCounter.increment();
      log.warn("HMAC signature mismatch on event_id={} event_type={} offset={}",
          envelope.eventId(), envelope.eventType(), record.offset());
      return false; // commit — replaying won't help
    }

    switch (envelope.eventType()) {
      case "payment.captured" -> dispatchCaptured(envelope);
      case "payment.refunded" -> dispatchRefunded(envelope);
      default -> log.warn("Unknown event_type={} at offset={} — committing and skipping",
          envelope.eventType(), record.offset());
    }
    processedCounter.increment();
    return true;
  }

  private void dispatchCaptured(PaymentEventEnvelope envelope) {
    // parse + construct may throw (bad payload JSON, malformed occurredAt). Let it propagate to
    // processRecord, which re-throws out to pollLoop, which then skips the offset commit so the
    // record is replayed on the next poll. Silent swallow here was C2 (closed 2026-07-09).
    PaymentCapturedPayload payload = objectMapper.readValue(
        envelope.payload(), PaymentCapturedPayload.class);
    PaymentCapturedEvent event = new PaymentCapturedEvent(
        payload.orderUuid(),
        payload.paymentIntentId(),
        payload.amountCents(),
        payload.currency(),
        parseOccurredAt(payload.occurredAt()));
    SignedPaymentCapturedEvent signed = new SignedPaymentCapturedEvent(
        envelope.eventId(),
        envelope.aggregateType(),
        envelope.aggregateId(),
        envelope.payload(),
        envelope.signatures(),
        event);
    applicationEventPublisher.publishEvent(signed);
  }

  private void dispatchRefunded(PaymentEventEnvelope envelope) {
    PaymentRefundedPayload payload = objectMapper.readValue(
        envelope.payload(), PaymentRefundedPayload.class);
    PaymentRefundedEvent event = new PaymentRefundedEvent(
        payload.orderUuid(),
        payload.paymentIntentId(),
        payload.amountCents(),
        payload.currency(),
        parseOccurredAt(payload.occurredAt()));
    SignedPaymentRefundedEvent signed = new SignedPaymentRefundedEvent(
        envelope.eventId(),
        envelope.aggregateType(),
        envelope.aggregateId(),
        envelope.payload(),
        envelope.signatures(),
        event);
    applicationEventPublisher.publishEvent(signed);
  }

  private static LocalDateTime parseOccurredAt(String occurredAt) {
    try {
      return OffsetDateTime.parse(occurredAt).toLocalDateTime();
    } catch (RuntimeException ignored) {
      return LocalDateTime.parse(occurredAt);
    }
  }
}
