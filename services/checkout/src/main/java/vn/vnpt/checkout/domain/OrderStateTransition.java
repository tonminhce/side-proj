package vn.vnpt.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.checkout.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * Order state-transition log — Story 2.5 / FR-22, FR-23 (ADR-12 / NFR-OBS-3).
 *
 * <p>Append-only. Every saga transition appends one row; {@code UPDATE} / {@code DELETE} are
 * forbidden. The recovery runner reads by {@code orderUuid} (ordered by {@code created_at ASC}) to
 * re-derive the saga step on startup replay.
 *
 * <p>{@code @IgnoreSoftUkAudit} — terminal FSM entity; soft-delete columns are inherited for schema
 * uniformity but never set.
 *
 * <p>{@code eventId} (Snowflake) is assigned by {@code BaseEntity.@PrePersist} and is unique —
 * duplicates signal a buggy writer.
 */
@Entity
@Table(name = "order_state_transition")
@IgnoreSoftUkAudit
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class OrderStateTransition extends BaseEntity {

  /** Saga event id (Snowflake; UNIQUE — append-only invariant). Same value as {@code uuid}. */
  @Column(name = "event_id", nullable = false, unique = true)
  private Long eventId;

  /** Snowflake id of the order (NO JPA FK — ADR-03 cross-aggregate reference). */
  @Column(name = "order_uuid", nullable = false)
  private Long orderUuid;

  /** Source state (nullable for the initial CREATED row). */
  @Enumerated(EnumType.STRING)
  @Column(name = "from_state", length = 32)
  private OrderStatus fromState;

  /** Destination state. */
  @Enumerated(EnumType.STRING)
  @Column(name = "to_state", nullable = false, length = 32)
  private OrderStatus toState;

  /** Saga step name (e.g. {@code "cart.submit"}, {@code "stock.reserve"}). */
  @Column(name = "saga_step", nullable = false, length = 64)
  private String sagaStep;

  /** Free-text reason for failure (e.g. {@code "INSUFFICIENT_STOCK"}); null on success rows. */
  @Column(name = "failure_reason", length = 128)
  private String failureReason;

  /** Convenience constructor for the saga orchestrator (excludes uuid — {@code @PrePersist} assigns). */
  public OrderStateTransition(Long orderUuid, OrderStatus fromState, OrderStatus toState,
      String sagaStep, String failureReason) {
    this.orderUuid = orderUuid;
    this.fromState = fromState;
    this.toState = toState;
    this.sagaStep = sagaStep;
    this.failureReason = failureReason;
  }

  /** Mirror uuid onto event_id so the UNIQUE constraint covers both. */
  @jakarta.persistence.PrePersist
  public void onPrePersist() {
    if (eventId == null) {
      eventId = getUuid();
    }
  }
}