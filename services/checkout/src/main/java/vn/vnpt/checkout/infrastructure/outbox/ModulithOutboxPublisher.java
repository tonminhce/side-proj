package vn.vnpt.checkout.infrastructure.outbox;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.checkout.application.port.OutboxPublisher;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Modulith outbox publisher — Story 2.3 (ADR-01 / ADR-04 / ADR-14 / ADR-20).
 *
 * <p>Mirrors {@code vn.vnpt.cart.infrastructure.outbox.ModulithOutboxPublisher} line-for-line with
 * two responsibilities: (1) persist the event to the {@code outbox} table in the same transaction
 * as the business state (ADR-04 atomicity); (2) publish via {@link ApplicationEventPublisher} so
 * in-process {@code @ApplicationModuleListener}s fire.
 *
 * <p>ponytail: checkout's events are plain Lombok {@code @Value} POJOs (not Avro
 * {@code SpecificRecord}), so the inventory publisher's Avro-to-Map branch is dropped — Jackson
 * serializes the event directly. The {@code signatures} map (computed by {@code CheckoutEventPublisher}
 * over the JCS-canonical payload) is persisted to the {@code outbox.signatures} JSONB column.
 */
@Component
public class ModulithOutboxPublisher implements OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(ModulithOutboxPublisher.class);

  private static final String SQL_INSERT_OUTBOX =
      "INSERT INTO outbox (aggregate_type, aggregate_id, event_type, event_id, payload, signatures)"
          + " VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher applicationEventPublisher;

  public ModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.applicationEventPublisher = applicationEventPublisher;
  }

  @Override
  public void append(
      String aggregateType,
      Long aggregateId,
      String eventType,
      Object event,
      Map<String, String> signatures) {
    long eventId = SnowflakeIdGenerator.generateId();
    String payloadJson = serialize(event);
    String signaturesJson = serializeSignatures(signatures);

    jdbcTemplate.update(
        SQL_INSERT_OUTBOX,
        aggregateType,
        aggregateId,
        eventType,
        eventId,
        payloadJson,
        signaturesJson);

    if (log.isDebugEnabled()) {
      log.debug(
          "Outbox appended: type={} id={} eventType={} eventId={}",
          aggregateType,
          aggregateId,
          eventType,
          eventId);
    }

    // Fire in-process listeners (none in Story 2.3; wired for Story 2.5's checkout.started consumer).
    applicationEventPublisher.publishEvent(event);
  }

  private String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to serialize outbox value " + value.getClass().getName(), e);
    }
  }

  /** Serialize the signatures Map to JSON. Empty Map → {@code "{}"}. */
  private String serializeSignatures(Map<String, String> signatures) {
    if (signatures == null || signatures.isEmpty()) {
      return "{}";
    }
    try {
      return objectMapper.writeValueAsString(signatures);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize signatures", e);
    }
  }

  /**
   * Jackson-based {@link EventSerializer} + no-op {@link EventPublicationRepository} the Modulith
   * autoconfig chain requires (the JDBC bridge repo is excluded in application.yml; the V001
   * {@code outbox} table is the canonical outbox and this publisher writes it directly).
   */
  @Configuration
  static class EventSerializerConfig {

    @Bean
    EventSerializer eventSerializer(ObjectMapper objectMapper) {
      return new EventSerializer() {
        @Override
        public Object serialize(Object event) {
          return objectMapper.valueToTree(event);
        }

        @Override
        public <T> T deserialize(Object serialized, Class<T> type) {
          return objectMapper.convertValue(serialized, type);
        }
      };
    }

    @Bean
    EventPublicationRepository eventPublicationRepository() {
      return new NoOpEventPublicationRepository();
    }
  }

  /** Empty repository: no JDBC persistence, no Kafka publish. In-process listener only. */
  private static final class NoOpEventPublicationRepository implements EventPublicationRepository {

    @Override
    public TargetEventPublication create(TargetEventPublication publication) {
      return publication;
    }

    @Override
    public void markProcessing(UUID identifier) {}

    @Override
    public void markCompleted(TargetEventPublication publication, Instant completionDate) {}

    @Override
    public void markCompleted(
        Object event, PublicationTargetIdentifier identifier, Instant completionDate) {}

    @Override
    public void markCompleted(UUID identifier, Instant completionDate) {}

    @Override
    public void markFailed(UUID identifier) {}

    @Override
    public boolean markResubmitted(UUID identifier, Instant resubmissionDate) {
      return true;
    }

    @Override
    public List<TargetEventPublication> findIncompletePublications() {
      return Collections.emptyList();
    }

    @Override
    public List<TargetEventPublication> findIncompletePublicationsPublishedBefore(Instant instant) {
      return Collections.emptyList();
    }

    @Override
    public Optional<TargetEventPublication> findIncompletePublicationsByEventAndTargetIdentifier(
        Object event, PublicationTargetIdentifier targetIdentifier) {
      return Optional.empty();
    }

    @Override
    public void deletePublications(List<UUID> identifiers) {}

    @Override
    public void deleteCompletedPublications() {}

    @Override
    public void deleteCompletedPublicationsBefore(Instant instant) {}
  }
}