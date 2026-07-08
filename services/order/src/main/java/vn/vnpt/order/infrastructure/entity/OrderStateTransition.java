package vn.vnpt.order.infrastructure.entity;

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
 * Order state transition — Story 4.1 / FR-30. Append-only log; the use case layer enforces
 * INSERT-only (no setter is exposed via the repository; the entity has setters for builder use
 * only, and the use case never calls {@code save()} on an existing entity).
 */
@Entity
@Table(name = "order_state_transition")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class OrderStateTransition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_uuid", nullable = false)
  private Long orderUuid;

  @Column(name = "from_state", length = 32)
  private String fromState;

  @Column(name = "to_state", nullable = false, length = 32)
  private String toState;

  @Column(name = "saga_step", nullable = false, length = 64)
  private String sagaStep;

  @Column(name = "event_id", nullable = false, unique = true)
  private Long eventId;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}