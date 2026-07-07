package vn.vnpt.checkout.application.port;

import java.util.Map;

/**
 * Outbox publisher port — application-layer contract for writing business events into the
 * service-local {@code outbox} table atomically with business state (ADR-14, ADR-04, ADR-20).
 *
 * <p>Intentionally identical to {@code vn.vnpt.cart.application.port.OutboxPublisher}. Cross-service
 * port sharing would require a common port module in {@code util/}; that's YAGNI — each service owns
 * its own port (see inventory Story 1.5 precedent).
 *
 * <p>The implementation in {@code vn.vnpt.checkout.infrastructure.outbox.ModulithOutboxPublisher}
 * writes the row via {@code JdbcTemplate} and fires {@code ApplicationEventPublisher.publishEvent} so
 * in-process {@code @ApplicationModuleListener}s consume the event in the same transaction.
 */
public interface OutboxPublisher {

  /**
   * Append an event to the outbox.
   *
   * @param aggregateType aggregate root name (e.g. {@code "Checkout"})
   * @param aggregateId Snowflake id of the aggregate root
   * @param eventType dotted event type string ({@code "checkout.started"})
   * @param event domain event instance (Jackson serializes via runtime class)
   * @param signatures per-service HMAC signature map (ADR-20). Pass {@link Map#of()} for unsigned.
   */
  void append(
      String aggregateType,
      Long aggregateId,
      String eventType,
      Object event,
      Map<String, String> signatures);
}