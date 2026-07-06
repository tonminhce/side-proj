package vn.vnpt.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-consumer processed-event record (ADR-04 / NFR-IDEM-1).
 *
 * <p>Maps to the {@code processed_event} table that V001 declared but left empty. The
 * UNIQUE {@code event_id} constraint is the cross-consumer dedup key — a redelivered event
 * from the outbox bridge hits the constraint and the listener treats it as a no-op.
 *
 * <p>Story 1.3 lands the first listener (CatalogEventLogger). The entity is intentionally
 * minimal — Story 1.5+ may add a {@code processed_at} index or per-tenant partitioning.
 */
@Entity
@Table(name = "processed_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class ProcessedEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "event_id", nullable = false, unique = true)
  private Long eventId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "processed_at", nullable = false)
  private LocalDateTime processedAt;

  @Column(name = "consumer", nullable = false, length = 128)
  private String consumer;
}
