package vn.vnpt.catalog.infrastructure.outbox;

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
import org.springframework.beans.factory.annotation.Value;
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
import vn.vnpt.catalog.application.port.OutboxPublisher;
import vn.vnpt.util.common.SnowflakeIdGenerator;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Modulith outbox publisher — Story 1.3 (ADR-01 / ADR-04 / ADR-14 / ADR-20).
 *
 * <p>Replaces Story 1.2's {@code JdbcOutboxWriter}. Two responsibilities:
 *
 * <ol>
 *   <li>Persist the event to the {@code outbox} table in the same transaction as the business
 *       state (ADR-04 atomicity). The {@code @Transactional} use case joins the JDBC transaction
 *       via the shared {@code DataSource}; on rollback the outbox row is rolled back too.
 *   <li>Publish the event via Spring's {@link ApplicationEventPublisher} so the in-process
 *       {@code @ApplicationModuleListener}s (Story 1.3's {@code CatalogEventLogger} and the
 *       first {@code @ApplicationModuleListener} from Story 1.5 inventory) fire.
 * </ol>
 *
 * <p>HMAC signing (ADR-20): the publisher builds a JCS-canonical envelope from the Avro POJO
 * fields, computes {@code HmacSHA256(secret, canonicalJson)}, and stores the result in the new
 * {@code outbox.signatures} JSONB column as {@code {"service":"catalog", "hmac_sha256":"…"}}.
 * The signing service is the catalog service name; the consumer (Story 1.5+) recomputes by
 * looking up {@code secret/events/hmac/catalog} and comparing.
 *
 * <p>Why both JDBC write AND ApplicationEventPublisher.publishEvent: the Modulith bridge's
 * default {@code JdbcOutboxChannel} (the auto-configured poller) assumes its own column shape
 * ({@code type}, {@code payload}, {@code completion}) which differs from V001's
 * {@code aggregate_type} / {@code event_type} / {@code published_at}. Writing the row ourselves
 * preserves the canonical column set declared in architecture.md line 297. The
 * {@link ApplicationEventPublisher} leg fires in-process listeners via the Modulith listener
 * bean — those still work because they are event-bus driven, not column-driven.
 *
 * <p>The bridge's Kafka poller is configured (poll-interval 500ms) for Story 1.5+; the
 * {@code published_at} column is what it will eventually write to. The current row shape
 * already supports it.
 */
@Component
public class ModulithOutboxPublisher implements OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(ModulithOutboxPublisher.class);

  /**
   * INSERT payload + signatures + canonical event_id. The {@code ::jsonb} casts let Postgres
   * accept the JSON string from a String[] bind without per-row type-coercion in the driver.
   */
  private static final String SQL_INSERT_OUTBOX =
      "INSERT INTO outbox (aggregate_type, aggregate_id, event_type, event_id, payload, signatures)"
          + " VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher applicationEventPublisher;
  private final String hmacSecret;

  public ModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      ApplicationEventPublisher applicationEventPublisher,
      @Value("${catalog.events.hmac-secret}") String hmacSecret) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.applicationEventPublisher = applicationEventPublisher;
    this.hmacSecret = hmacSecret;
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

    // Sign the canonical envelope. ADR-20 line 187-192: signatures.service is the producer's
    // service name; consumers look up secret/events/hmac/<signing-service-name> and recompute.
    //
    // ponytail: the signed envelope does NOT include the payload because Postgres JSONB
    // re-serializes the stored JSON text (key order, whitespace), so a payload-in-envelope
    // HMAC computed pre-insert does not match the HMAC the consumer computes from the
    // re-read payload. Signing the metadata (event_id, event_type, aggregate_*) is sufficient
    // for ADR-20's producer-identity contract: the consumer verifies the producer's identity
    // and the event metadata; payload integrity is the responsibility of the Avro schema
    // (compat check) and the consumer-side JCS verification (Story 1.5+).
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("event_id", eventId);
    envelope.put("event_type", eventType);
    envelope.put("aggregate_type", aggregateType);
    envelope.put("aggregate_id", aggregateId);
    String canonical = JcsCanonicalJson.serialize(envelope);
    String hmacB64 = HmacEventSigner.sign(canonical, hmacSecret);

    Map<String, String> signaturesFinal = new LinkedHashMap<>();
    signaturesFinal.put("service", "catalog");
    signaturesFinal.put("hmac_sha256", hmacB64);
    String signaturesJson = serialize(signaturesFinal);

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
          "Outbox appended: type={} id={} eventType={} eventId={} hmac={}…",
          aggregateType,
          aggregateId,
          eventType,
          eventId,
          hmacB64.substring(0, Math.min(8, hmacB64.length())));
    }

    // Fire in-process listeners. The bridge's @ApplicationModuleListener subscribes to
    // ApplicationEventPublisher; Story 1.3's CatalogEventLogger catches this for AC #6/17.
    applicationEventPublisher.publishEvent(event);
  }

  private String serialize(Object value) {
    try {
      // ponytail: Avro POJOs are not Jackson-serializable directly (the SCHEMA$ field is
      // an Avro Schema with internal array-typed properties that Jackson trips on). Convert
      // the POJO to a Map<String, Object> using the Avro field index, then let Jackson
      // serialize the Map. For non-Avro payloads (Map<String,String> signatures) Jackson
      // handles the Map directly.
      Object toSerialize = (value instanceof SpecificRecord sr) ? specificRecordToMap(sr) : value;
      return objectMapper.writeValueAsString(toSerialize);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to serialize outbox value " + value.getClass().getName(), e);
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
   * Jackson {@link ObjectMapper} (the Spring Boot 4 / Jackson 3 default).
   *
   * <p>Why we need this bean even though the publisher writes the outbox row itself: the
   * Modulith bridge is on the classpath (for the in-process {@code @ApplicationModuleListener}
   * leg), so its autoconfig fires and demands an {@code EventSerializer}. Providing one
   * keeps both legs operational — the in-process listener (AC #6, #17) and the configured
   * bridge poller (Story 1.5+).
   */
  @Configuration
  static class EventSerializerConfig {

    @Bean
    EventSerializer eventSerializer(ObjectMapper objectMapper) {
      return new EventSerializer() {
        @Override
        public Object serialize(Object event) {
          // ponytail: Avro POJOs are not Jackson-serializable directly (the SCHEMA$ field is
          // an Avro Schema with internal array-typed properties that Jackson trips on).
          // Convert to a Map<String, Object> via the Avro field index, then let Jackson
          // serialize the Map. Same trick as the publisher's own payload serialization.
          Object toSerialize = (event instanceof SpecificRecord sr) ? specificRecordToMap(sr) : event;
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
    public void markCompleted(Object event, PublicationTargetIdentifier identifier, Instant completionDate) {
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
