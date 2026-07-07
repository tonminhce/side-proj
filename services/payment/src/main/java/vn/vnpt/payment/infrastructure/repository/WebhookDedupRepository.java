package vn.vnpt.payment.infrastructure.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.vnpt.payment.application.port.WebhookDedupPort;
import vn.vnpt.payment.infrastructure.entity.WebhookDedup;

/**
 * Spring Data JPA repository for {@link WebhookDedup} — Stripe producer-side dedup (FR-26, ADR-21).
 * Mirrors {@code services/catalog/.../ProcessedEventRepository} verbatim: implements the port so
 * the use case (which lives in {@code application.usecase}) can inject the port, not the repo.
 *
 * <p>{@code insertRow} uses {@code INSERT ... ON CONFLICT (event_id) DO NOTHING} — Postgres-only
 * (the dialect is locked to PostgreSQL per {@code services/payment/application.yml}). The default
 * {@link #append} method maps rows-affected (0 or 1) to {@code AppendOutcome.inserted=true|false};
 * the use case's existsBy check in {@link #existsByEventId} is the catalog-style guard.
 */
@Repository
public interface WebhookDedupRepository extends JpaRepository<WebhookDedup, String>, WebhookDedupPort {

  @Override
  boolean existsByEventId(String eventId);

  @Modifying
  @Query(
      value =
          "INSERT INTO webhook_dedup (event_id, event_type, livemode, received_at)"
              + " VALUES (:eventId, :eventType, :livemode, :receivedAt)"
              + " ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true)
  int insertRow(
      @Param("eventId") String eventId,
      @Param("eventType") String eventType,
      @Param("livemode") boolean livemode,
      @Param("receivedAt") LocalDateTime receivedAt);

  @Override
  default AppendOutcome append(String eventId, String eventType, boolean livemode, LocalDateTime receivedAt) {
    int rows = insertRow(eventId, eventType, livemode, receivedAt);
    return new AppendOutcome(rows > 0, rows > 0 ? receivedAt : null);
  }
}