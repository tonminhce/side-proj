package vn.vnpt.catalog.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.catalog.application.port.OutboxPublisher;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Outbox writer for Story 1.2. Writes directly to the {@code outbox} table via {@link JdbcTemplate}.
 *
 * <p>This is the {@link Primary} {@link OutboxPublisher} for Story 1.2. In Story 1.3, Spring
 * Modulith's outbox bridge ({@code spring-modulith-events-jdbc}) takes over as the canonical
 * publisher and the {@link Primary} annotation is removed.
 *
 * <p>Why direct JDBC for Story 1.2 (ponytail: land the persistence leg first, the bridge in the
 * next story): the Modulith bridge is the dual-write — it persists AND publishes in-process. Story
 * 1.2 only persists; the bridge arrives in Story 1.3 and is wired as a {@code @Component} that
 * listens to in-memory {@code ApplicationEventPublisher} events. Replacing this writer with the
 * bridge is a one-class change.
 *
 * <p>Atomicity: when called from a {@code @Transactional} use case, the JDBC insert joins the
 * JPA transaction (both share the same {@code DataSource}). On rollback, the outbox row is
 * rolled back too — ADR-04 invariant.
 */
@Component
@Primary
public class JdbcOutboxWriter implements OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(JdbcOutboxWriter.class);

  private static final String SQL_INSERT_OUTBOX =
      "INSERT INTO outbox (aggregate_type, aggregate_id, event_type, event_id, payload)"
          + " VALUES (?, ?, ?, ?, ?::jsonb)";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public JdbcOutboxWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  @Override
  public void append(String aggregateType, Long aggregateId, String eventType, Object event) {
    String payloadJson = serialize(event);
    long eventId = SnowflakeIdGenerator.generateId();
    jdbcTemplate.update(
        SQL_INSERT_OUTBOX, aggregateType, aggregateId, eventType, eventId, payloadJson);
    if (log.isDebugEnabled()) {
      log.debug(
          "Outbox appended: type={} id={} eventType={} eventId={}",
          aggregateType,
          aggregateId,
          eventType,
          eventId);
    }
  }

  private String serialize(Object event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JacksonException e) {
      throw new IllegalStateException(
          "Failed to serialize outbox event " + event.getClass().getName(), e);
    }
  }
}
