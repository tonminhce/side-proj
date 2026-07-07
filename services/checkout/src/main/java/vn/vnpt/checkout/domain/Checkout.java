package vn.vnpt.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import vn.vnpt.checkout.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * Checkout aggregate root — Story 2.3 / FR-19, FR-21.
 *
 * <p>Finite-state machine: {@code CREATED → PAYMENT_PENDING → PAID/FAILED/CANCELLED/EXPIRED}. Single-page
 * checkout API owns the initial {@code PAYMENT_PENDING} state; Story 2.5's saga orchestrator drives
 * transitions to terminal states based on {@code order.paid} / {@code payment_intent.payment_failed}
 * events.
 *
 * <p>{@code @IgnoreSoftUkAudit} — Checkout is a finite-state machine (terminal FSM entity); soft-delete
 * columns inherited from {@code RootEntity} for schema uniformity but never set by any use case. The
 * natural key saga uses for idempotency is {@code (checkoutUuid, saga_step_name)} per ADR-11, not
 * {@code (tenantId, ...)}.
 *
 * <p>{@code cartUuid} is a cross-service reference to {@code cart_db.carts.uuid} with NO FK (database
 * per service, ADR-03). {@code userId} is nullable (guest checkout per FR-19). {@code @Version} gives
 * optimistic concurrency (the saga uses {@code If-Match} headers in Story 2.5 for OPM).
 *
 * <p>Implements {@link Persistable} so {@code save()} routes new entities through {@code persist()}
 * even when the Snowflake UUID is pre-assigned (Story 2.4 / FR-20 AC #4 — the idempotency key
 * {@code (checkoutUuid, "stripe.payment_intent.create")} must match the persisted UUID).
 */
@Entity
@Table(name = "checkouts")
@IgnoreSoftUkAudit
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class Checkout extends BaseEntity implements Persistable<Long> {

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

  /** Auth user id (nullable — guest checkout per FR-19). */
  @Column(name = "user_id", length = 64)
  private String userId;

  /** Cookie UUID (nullable when user is logged in). */
  @Column(name = "guest_cart_id", length = 64)
  private String guestCartId;

  /** Lifecycle status — see {@link CheckoutStatus}. */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private CheckoutStatus status;

  /** Optimistic-concurrency version (saga uses {@code If-Match} in Story 2.5). */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** ADR-20 + Story 2.4 forward-compat passthrough; nullable until Stripe PaymentIntent lands. */
  @Column(name = "stripe_client_secret", columnDefinition = "TEXT")
  private String stripeClientSecret;

  /** Story 2.4 / FR-20 — Stripe {@code pi_...} id from PaymentIntent.create(). Nullable pre-2.4. */
  @Column(name = "payment_intent_id", length = 64)
  private String paymentIntentId;

  /** Embedded shipping address. */
  @Embedded
  private ShippingAddress shippingAddress;

  /** Defaults {@code status = PAYMENT_PENDING} + {@code tenantId = "default"} at insert time. */
  @PrePersist
  public void onPrePersist() {
    if (status == null) {
      status = CheckoutStatus.PAYMENT_PENDING;
    }
    if (tenantId == null) {
      tenantId = "default";
    }
  }
}