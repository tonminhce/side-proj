package vn.vnpt.inventory.infrastructure.outbox;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
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
import vn.vnpt.inventory.application.port.OutboxPublisher;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Modulith outbox publisher — Story 1.5 (ADR-01 / ADR-04 / ADR-14); extended in Story 1.6 for
 * ADR-20 producer-side HMAC signing.
 *
 * <p>Two responsibilities:
 *
 * <ol>
 *   <li>Persist the event to the {@code outbox} table in the same transaction as the business
 *       state (ADR-04 atomicity). The {@code @Transactional} use case joins the JDBC transaction
 *       via the shared {@code DataSource}; on rollback the outbox row is rolled back too.
 *   <li>Publish the event via Spring's {@link ApplicationEventPublisher} so the in-process
 *       {@code @ApplicationModuleListener}s (Story 1.5's {@code CatalogEventListener} is the first)
 *       fire.
 * </ol>
 *
 * <p>Story 1.6 closes the ADR-20 producer-side gap for inventory outbound events: the
 * {@code signatures} map is now persisted to the {@code outbox.signatures} JSONB column (V004).
 * The CALLER (use case) computes the HMAC over the JCS-canonical payload; this publisher only
 * persists the supplied signatures map. The publisher is intentionally dumb — single
 * responsibility: serialize payload + persist signatures to row + fire in-process event.
 *
 * <p>Why both JDBC write AND {@code ApplicationEventPublisher.publishEvent}: the Modulith
 * bridge's default {@code JdbcOutboxChannel} assumes its own column shape ({@code type},
 * {@code payload}, {@code completion}) which differs from V001's {@code aggregate_type} /
 * {@code event_type} / {@code published_at}. Writing the row ourselves preserves the canonical
 * column set declared in architecture.md line 297. The {@link ApplicationEventPublisher} leg
 * fires in-process listeners via the Modulith listener bean — those still work because they are
 * event-bus driven, not column-driven.
 */
@Component
public class ModulithOutboxPublisher implements OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(ModulithOutboxPublisher.class);

  /**
   * INSERT payload + signatures. The {@code ::jsonb} casts let Postgres accept the JSON strings
   * from String[] binds without per-row type-coercion in the driver.
   */
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
    // ponytail: caller-supplied signatures Map is JSON-serialized as-is. Empty Map.of() yields
    // "{}" (signed off — Story 1.5's AdjustInventoryUseCase passes Map.of() and the V004 column
    // accepts JSON null OR empty object). Pre-1.6 callers (AdjustInventoryUseCase) continue to
    // pass Map.of(); their events remain unsigned (matches Story 1.5 design).
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

    // Fire in-process listeners. The bridge's @ApplicationModuleListener subscribes to
    // ApplicationEventPublisher; Story 1.5's CatalogEventListener catches CatalogProductCreated
    // for AC #14.
    applicationEventPublisher.publishEvent(event);
  }

  private String serialize(Object value) {
    try {
      // ponytail: Avro POJOs are not Jackson-serializable directly (the SCHEMA$ field is
      // an Avro Schema with internal array-typed properties that Jackson trips on). Convert
      // the POJO to a Map<String, Object> using the Avro field index, then let Jackson
      // serialize the Map. For non-Avro payloads Jackson handles the object directly.
      Object toSerialize = (value instanceof SpecificRecord sr) ? specificRecordToMap(sr) : value;
      return objectMapper.writeValueAsString(toSerialize);
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

  /** Convert an Avro {@link SpecificRecord} to a {@code LinkedHashMap} preserving field order. */
  private static Map<String, Object> specificRecordToMap(SpecificRecord record) {
    var schema = record.getSchema();
    Map<String, Object> out = new LinkedHashMap<>();
    for (var field : schema.getFields()) {
      out.put(field.name(), record.get(field.pos()));
    }
    return out;
  }

  /**
   * Jackson-based {@link EventSerializer} bean that the Modulith JDBC bridge requires.
   * The bridge's autoconfig ({@code JdbcEventPublicationAutoConfiguration}) is in the
   * {@code spring-modulith-events-jdbc} artifact and depends on this bean to serialize
   * events to JSONB. We provide a thin implementation that delegates to the autoconfigured
   * Jackson {@link ObjectMapper}.
   */
  @Configuration
  static class EventSerializerConfig {

    @Bean
    EventSerializer eventSerializer(ObjectMapper objectMapper) {
      return new EventSerializer() {
        @Override
        public Object serialize(Object event) {
          // ponytail: Avro POJOs are not Jackson-serializable directly. Convert to a
          // Map<String, Object> via the Avro field index, then let Jackson serialize the Map.
          Object toSerialize =
              (event instanceof SpecificRecord sr) ? specificRecordToMap(sr) : event;
          return objectMapper.valueToTree(toSerialize);
        }

        @Override
        public <T> T deserialize(Object serialized, Class<T> type) {
          return objectMapper.convertValue(serialized, type);
        }
      };
    }

    /**
     * No-op {@link EventPublicationRepository}. The Modulith autoconfig chain requires this
     * bean to be present so {@code EventPublicationAutoConfiguration} can wire its
     * {@code EventPublicationRegistry} and {@code PersistentApplicationEventMulticaster}
     * (the in-process {@code @ApplicationModuleListener} leg). We provide a no-op repo
     * because the V001 {@code outbox} table is the canonical outbox — our
     * {@link ModulithOutboxPublisher} writes to it directly. The bridge's JDBC
     * {@code JdbcEventPublicationRepository} is excluded (see application.yml); the
     * Kafka publish leg is a Story 1.5+ concern.
     */
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
    public void markProcessing(UUID identifier) {
      // no-op
    }

    @Override
    public void markCompleted(TargetEventPublication publication, Instant completionDate) {
      // no-op
    }

    @Override
    public void markCompleted(
        Object event, PublicationTargetIdentifier identifier, Instant completionDate) {
      // no-op
    }

    @Override
    public void markCompleted(UUID identifier, Instant completionDate) {
      // no-op
    }

    @Override
    public void markFailed(UUID identifier) {
      // no-op
    }

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
    public void deletePublications(List<UUID> identifiers) {
      // no-op
    }

    @Override
    public void deleteCompletedPublications() {
      // no-op
    }

    @Override
    public void deleteCompletedPublicationsBefore(Instant instant) {
      // no-op
    }
  }
}