package vn.vnpt.payment.application.port;

import java.time.LocalDateTime;

/**
 * Application port for the Stripe webhook dedup table (FR-26, ADR-21). The use case depends on
 * this port, not on the repository — the {@code application.usecase → infrastructure.repository}
 * ArchUnit rule (Story 3.2 / Task 6) makes the port the seam.
 *
 * <p>ponytail: {@code AppendOutcome} is a {@code record} — Java's boring "did I just insert, or
 * am I replaying?" enum-like return type. Postgres's {@code INSERT ... ON CONFLICT (event_id) DO
 * NOTHING} returns 0 or 1 rows-affected; the repository maps that to {@code inserted=true|false}.
 */
public interface WebhookDedupPort {

  /** @return inserted=true iff this call wrote a new row; inserted=false iff the key already existed. */
  AppendOutcome append(String eventId, String eventType, boolean livemode, LocalDateTime receivedAt);

  boolean existsByEventId(String eventId);

  record AppendOutcome(boolean inserted, LocalDateTime receivedAt) {}
}