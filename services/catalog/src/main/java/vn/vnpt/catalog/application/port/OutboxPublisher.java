package vn.vnpt.catalog.application.port;

/**
 * Outbox publisher port — application layer contract for writing business events into the
 * service-local {@code outbox} table atomically with business state (ADR-14, ADR-04).
 *
 * <p>The implementation (in {@code vn.vnpt.catalog.infrastructure.outbox.JdbcOutboxWriter} for
 * Story 1.2; replaced by a Spring Modulith outbox bridge in Story 1.3) serializes the event
 * payload via Jackson and inserts the row via JDBC. Writes run inside the caller's transaction.
 *
 * <p>Generic {@code Object} event payload: the port does not introspect the event class — that
 * would be a strategy class for one event type, YAGNI. Story 1.3's Avro-generated types are also
 * passed as {@code Object}; the writer serializes by class.
 */
public interface OutboxPublisher {
  /**
   * Append an event to the outbox.
   *
   * @param aggregateType aggregate root name (e.g. {@code "Product"}) — driver of the
   *     {@code outbox.aggregate_type} column
   * @param aggregateId Snowflake ID of the aggregate root (the {@code Product.uuid})
   * @param eventType dotted event type string ({@code "catalog.product.created"}, used as the
   *     Kafka topic name in Story 1.3)
   * @param event domain event instance (any type — Jackson serializes via runtime class)
   */
  void append(String aggregateType, Long aggregateId, String eventType, Object event);
}
