package vn.vnpt.util.events;

import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.stereotype.Component;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Modulith JDBC outbox publisher base — see ADR-04 / ADR-14 / ADR-20. Subclasses implement
 * {@link #signaturesFor(long, String, String, long, String)}; return {@code Map.of()} for unsigned events.
 */
@Component
public abstract class ModulithOutboxPublisher {

  private static final String SQL_INSERT_OUTBOX =
      "INSERT INTO outbox (aggregate_type, aggregate_id, event_type, event_id, payload, signatures)"
          + " VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)";

  protected final JdbcTemplate jdbcTemplate;
  protected final ObjectMapper objectMapper;
  protected final ApplicationEventPublisher applicationEventPublisher;

  protected ModulithOutboxPublisher(
      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ApplicationEventPublisher applicationEventPublisher) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.applicationEventPublisher = applicationEventPublisher;
  }

  /** Override to add HMAC. Default: no signing (Map.of()). */
  protected Map<String, String> signaturesFor(
      long eventId, String eventType, String aggregateType, long aggregateId, String payloadJson) {
    return Map.of();
  }

  public final void append(
      String aggregateType, Long aggregateId, String eventType, Object event, Map<String, String> callerSignatures) {
    long eventId = SnowflakeIdGenerator.generateId();
    String payloadJson = serialize(event);
    Map<String, String> signatures = merge(
        signaturesFor(eventId, eventType, aggregateType, aggregateId, payloadJson), callerSignatures);
    jdbcTemplate.update(
        SQL_INSERT_OUTBOX, aggregateType, aggregateId, eventType, eventId, payloadJson, serialize(signatures));
    applicationEventPublisher.publishEvent(event);
  }

  private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
    if (a == null || a.isEmpty()) return b == null ? Map.of() : b;
    if (b == null || b.isEmpty()) return a;
    Map<String, String> out = new LinkedHashMap<>(a);
    out.putAll(b);
    return out;
  }

  protected String serialize(Object value) {
    try {
      Object toSerialize = (value instanceof SpecificRecord sr) ? specificRecordToMap(sr) : value;
      return objectMapper.writeValueAsString(toSerialize);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize outbox value " + value.getClass().getName(), e);
    }
  }

  protected static Map<String, Object> specificRecordToMap(SpecificRecord record) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var field : record.getSchema().getFields()) {
      out.put(field.name(), record.get(field.pos()));
    }
    return out;
  }

  /** Jackson EventSerializer + NoOpEventPublicationRepository beans — required by Modulith autoconfig. */
  @Configuration
  public static class ModulithBridgeSupport {

    @Bean
    EventSerializer eventSerializer(ObjectMapper objectMapper) {
      return new EventSerializer() {
        @Override public Object serialize(Object event) {
          Object toSerialize = (event instanceof SpecificRecord sr) ? specificRecordToMap(sr) : event;
          return objectMapper.valueToTree(toSerialize);
        }
        @Override public <T> T deserialize(Object serialized, Class<T> type) {
          return objectMapper.convertValue(serialized, type);
        }
      };
    }

    @Bean
    EventPublicationRepository eventPublicationRepository() {
      return NoOpEventPublicationRepository.INSTANCE;
    }
  }
}
