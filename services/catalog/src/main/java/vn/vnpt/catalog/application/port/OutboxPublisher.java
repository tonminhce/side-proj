package vn.vnpt.catalog.application.port;

import java.util.Map;

/**
 * Outbox publisher port — application layer contract for writing business events into the
 * service-local {@code outbox} table atomically with business state (ADR-14, ADR-04, ADR-20).
 *
 * <p>Story 1.2 shipped a 4-arg signature with the use case passing a hand-written record. Story
 * 1.3 (a) replaces the hand-written event with an Avro-generated type and (b) extends the
 * signature with a {@code signatures} map that the publisher forwards to the {@code outbox}
 * JSONB column for HMAC event signing (ADR-20). Pass {@link Map#of()} when the publisher
 * is expected to compute the signature itself (the production path).
 *
 * <p>The implementation (in {@code vn.vnpt.catalog.infrastructure.outbox.ModulithOutboxPublisher}
 * for Story 1.3) writes the row via Spring Modulith's {@code ApplicationEventPublisher}; the
 * outbox bridge takes care of transaction joining, JSON serialization, and Kafka publish.
 *
 * <p>Generic {@code Object} event payload: the port does not introspect the event class — that
 * would be a strategy class for one event type, YAGNI. Story 1.3's Avro-generated types are also
 * passed as {@code Object}; the bridge serializes by class.
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
   * @param signatures per-service signature map ({@code {service: "catalog", hmac_sha256:
   *     "..."}}). Pass {@link Map#of()} when the publisher computes and supplies the
   *     signature itself; the port accepts either path so call sites stay agnostic.
   */
  void append(
      String aggregateType,
      Long aggregateId,
      String eventType,
      Object event,
      Map<String, String> signatures);
}
