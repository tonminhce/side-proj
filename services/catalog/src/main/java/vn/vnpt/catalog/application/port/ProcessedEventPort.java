package vn.vnpt.catalog.application.port;

import java.time.LocalDateTime;

/**
 * Application port for the consumer-side idempotency table (ADR-04 / NFR-IDEM-1).
 *
 * <p>Why a port here: the {@code CatalogEventLogger} lives under {@code application.event}
 * (per the canonical Modulith listener location) but the actual {@code processed_event}
 * table is infrastructure. The {@code application_doesNotDependOnInfrastructure} ArchUnit
 * rule (Story 1.3 / AC #15) requires the listener to depend on this port, not on the
 * repository. The port has a single method — the {@code append} insert with
 * {@code ON CONFLICT DO NOTHING} — which is the only thing the listener needs.
 */
public interface ProcessedEventPort {
  /**
   * Record that {@code consumer} processed {@code eventId}. Idempotent: a duplicate
   * {@code eventId} is silently dropped (the implementation uses {@code ON CONFLICT DO
   * NOTHING} on the UNIQUE constraint).
   */
  void append(String consumer, String eventType, Long eventId, LocalDateTime processedAt);
}
