package vn.vnpt.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import vn.vnpt.checkout.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.checkout.domain.exception.OrderIllegalStateTransitionException;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * Order aggregate root — Story 2.5 / FR-22, FR-23 (ADR-12 intra-Modulith saga).
 *
 * <p>The saga orchestrator ({@code OrderSagaOrchestrator}) creates a new {@code Order} on
 * {@code CheckoutStartedEvent} and drives transitions through {@code STOCK_RESERVED} →
 * {@code PAYMENT_PENDING} (and the {@code CREATED → FAILED} insufficient-stock branch). Terminal
 * transitions land in Epic 3 (Stripe webhook → PAID/FAILED) and Epic 4 (post-payment lifecycle).
 *
 * <p>{@code @IgnoreSoftUkAudit} — Order is a finite-state-machine row; soft-delete columns from
 * {@link BaseEntity} are inherited for schema uniformity but never set by any use case. The saga's
 * idempotency key is the natural {@code (checkoutUuid, saga_step_name)} tuple per ADR-11.
 *
 * <p>{@code cartUuid} and {@code checkoutUuid} are cross-service references with NO FK
 * (ADR-03 — database per service). {@code paymentIntentId} is a denormalized copy from
 * {@code Checkout.paymentIntentId} so downstream consumers (notification, fulfillment — Epic 4/9) can
 * correlate without a join.
 *
 * <p>Implements {@link Persistable} so {@code save()} routes new entities through {@code persist()}
 * even when the Snowflake UUID is pre-assigned (Story 2.4 C1 fix — the builder does NOT call
 * {@code setUuid(...)}; {@code BaseEntity.@PrePersist} assigns the uuid + version=0).
 */
@Entity
@Table(name = "orders")
@IgnoreSoftUkAudit
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class Order extends BaseEntity implements Persistable<Long> {

  @Transient
  private boolean isNewFlag = true;

  @PostLoad
  @PostPersist
  void markPersisted() {
    this.isNewFlag = false;
  }

  @Override
  public Long getId() {
    return getUuid();
  }

  @Override
  public boolean isNew() {
    return isNewFlag;
  }

  /** Single-tenant default ({@code "default"}). */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /** Cross-service reference to {@code cart_db.carts.uuid} (NO FK — ADR-03). */
  @Column(name = "cart_uuid", nullable = false)
  private Long cartUuid;

  /** Cross-service reference to {@code checkout_db.checkouts.uuid} (NO FK — same DB, saga co-located). */
  @Column(name = "checkout_uuid", nullable = false, unique = true)
  private Long checkoutUuid;

  /** Stripe {@code pi_...} id (non-secret per R-15 / ADR-23; denormalized copy from Checkout). */
  @Column(name = "payment_intent_id", length = 64)
  private String paymentIntentId;

  /** Embedded shipping address (snapshot from checkout). */
  @Embedded
  private ShippingAddress shippingAddress;

  /** Lifecycle status — see {@link OrderStatus}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private OrderStatus status;

  /** Optimistic-concurrency version (saga uses {@code @Version} for the FSM lock). */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** Saga step that drove the failure (e.g. {@code "stock.reserve"}); null unless status=FAILED. */
  @Column(name = "failure_saga_step", length = 64)
  private String failureSagaStep;

  /** Free-text reason for failure (e.g. {@code "INSUFFICIENT_STOCK"}). */
  @Column(name = "failure_reason", length = 64)
  private String failureReason;

  /** Append-only list of transitions — populated by {@link #transitionTo}. */
  @Transient
  @Builder.Default
  private final List<OrderStateTransition> pendingTransitions = new ArrayList<>();

  /** Default tenant + status at insert time. */
  @jakarta.persistence.PrePersist
  public void onPrePersist() {
    if (status == null) {
      status = OrderStatus.CREATED;
    }
    if (tenantId == null) {
      tenantId = "default";
    }
  }

  /**
   * FSM transition — records the from/to/sagaStep and stages a transition-log row in
   * {@link #pendingTransitions}. The orchestrator flushes the staged rows in the same transaction
   * (append-only invariant — see {@link OrderStateTransition}).
   *
   * @throws OrderIllegalStateTransitionException if {@code this → to} is not in the allowed matrix.
   */
  public void transitionTo(OrderStatus to, String sagaStep, String failureReason) {
    if (status == null) {
      throw new OrderIllegalStateTransitionException(null, to, sagaStep,
          "Order has no current status");
    }
    if (!status.canTransitionTo(to)) {
      throw new OrderIllegalStateTransitionException(status, to, sagaStep,
          "Transition not allowed by FSM");
    }
    OrderStatus from = this.status;
    this.status = to;
    this.failureSagaStep = failureReason == null ? null : sagaStep;
    this.failureReason = failureReason;
    pendingTransitions.add(new OrderStateTransition(getUuid(), from, to, sagaStep, failureReason));
  }
}