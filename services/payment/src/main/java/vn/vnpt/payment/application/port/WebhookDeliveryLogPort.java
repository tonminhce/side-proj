package vn.vnpt.payment.application.port;

/**
 * Application port for the {@code webhook_delivery_log} test-observability shim (Story 3.2 / Task 5).
 * Plain JPA {@code save} — duplicates are fine here; the log is a firehose.
 *
 * <p>Deleted by Story 3.5 when real {@code payment.captured} / {@code payment.refunded} outbox
 * events land (ADR-20).
 */
public interface WebhookDeliveryLogPort {

  void record(String eventId, String eventType, String sideEffectsRecorded);
}