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

// TEST-OBSERVABILITY SHIM — deleted by Story 3.5 when real outbox events land.
/**
 * Placeholder firehose for webhook delivery (Story 3.2 / Task 5). Production logic MUST NEVER read
 * this table; the use case writes one row per Stripe delivery to prove the side-effect path runs
 * exactly once per {@code event.id}. Replaced by the {@code payment.captured} / {@code
 * payment.refunded} outbox events in Story 3.5 (HMAC-signed outbox per ADR-20).
 *
 * <p>{@code eventId} is the {@code @Id} — no surrogate, no UNIQUE. Duplicates at the JPA layer
 * resolve to an UPDATE on re-save; the use case only calls {@code record(...)} on first delivery
 * (when {@code AppendOutcome.inserted=true}) so re-saves don't happen in practice.
 */
@Entity
@Table(name = "webhook_delivery_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class WebhookDeliveryLog {

  @Id
  @Column(name = "event_id", nullable = false, length = 128)
  private String eventId;

  @Column(name = "event_type", nullable = false, length = 128)
  private String eventType;

  @Column(name = "received_at", nullable = false)
  private LocalDateTime receivedAt;

  @Column(name = "side_effects_recorded", columnDefinition = "text")
  private String sideEffectsRecorded;
}