package vn.vnpt.payment.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Stripe webhook dedup row (FR-26, ADR-21, NFR-IDEM-1, R-03). PK is Stripe's opaque {@code event.id}
 * verbatim — no surrogate, no transformation (case-sensitive, no trim). The natural key IS the
 * dedup contract; {@code livemode} is observability only (AC #6: Stripe namespaces {@code event.id}
 * by livemode at the API level, so a byte-for-byte cross-mode duplicate is a Stripe API violation,
 * not a dedup concern).
 */
@Entity
@Table(name = "webhook_dedup")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class WebhookDedup {

  @Id
  @Column(name = "event_id", nullable = false, length = 128)
  private String eventId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "received_at", nullable = false)
  private LocalDateTime receivedAt;

  @Column(name = "livemode", nullable = false)
  private boolean livemode;

  @Column(name = "processed_at")
  private LocalDateTime processedAt;
}