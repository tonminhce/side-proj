package vn.vnpt.catalog.infrastructure.repository;

import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import vn.vnpt.catalog.application.port.ProcessedEventPort;
import vn.vnpt.catalog.domain.ProcessedEvent;

/**
 * Spring Data JPA repository for {@link ProcessedEvent} — consumer idempotency table
 * (Story 1.3 / ADR-04 / NFR-IDEM-1).
 *
 * <p>Implements the {@link ProcessedEventPort} application-layer contract so the in-process
 * listener (which lives in {@code application.event} and must not depend on
 * {@code infrastructure.repository} per the archunit rule) can inject the port. The
 * Spring {@code JpaRepository} auto-implementation handles {@code existsByEventId}.
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long>, ProcessedEventPort {

  boolean existsByEventId(Long eventId);

  @Override
  @Modifying
  @Query(
      value =
          "INSERT INTO processed_event (event_id, event_type, processed_at, consumer)"
              + " VALUES (:eventId, :eventType, :processedAt, :consumer)"
              + " ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true)
  void append(
      @Param("consumer") String consumer,
      @Param("eventType") String eventType,
      @Param("eventId") Long eventId,
      @Param("processedAt") LocalDateTime processedAt);
}
