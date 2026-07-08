package vn.vnpt.payment.application.port;

import java.util.Map;

/**
 * Outbox-publisher port for payment-domain events — Story 3.5 follow-up / FR-28.
 *
 * <p>The use case depends on this interface, not the concrete
 * {@code PaymentModulithOutboxPublisher}, so unit tests can mock the seam without
 * fighting Mockito's {@code mock-maker-subclass} (which cannot mock final classes).
 *
 * <p>Implementation contract: the impl inserts a row into the {@code outbox} table with the
 * HMAC-signed envelope (ADR-20 / AT-03) and publishes the event in-process. The
 * {@code callerSignatures} parameter allows extra signature entries to be merged on top of
 * the producer's own (default empty).
 */
public interface PaymentOutboxPublisher {

  /**
   * @param aggregateType free-form aggregate type (e.g. {@code "Payment"})
   * @param aggregateId   aggregate id (e.g. the numeric portion of the Stripe PaymentIntent id)
   * @param eventType     the event type (e.g. {@code "payment.captured"})
   * @param event         the event payload — will be JSON-serialized
   * @param callerSignatures extra HMAC signature entries to merge over the producer's own
   */
  void append(String aggregateType, Long aggregateId, String eventType,
              Object event, Map<String, String> callerSignatures);
}