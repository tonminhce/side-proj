package vn.vnpt.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.util.common.entity.base.BaseEntity;
import vn.vnpt.util.component.softdelete.annotation.SoftUk;

/**
 * Cart aggregate root — Story 2.1 / FR-14, FR-16.
 *
 * <p>An ANONYMOUS cart is bound to a cookie {@code guestCartId}; an ACTIVE cart is bound to an auth
 * {@code userId}. The two partial unique indexes ({@code uq_carts_tenant_guest} +
 * {@code uq_carts_tenant_user}, V001) keep the natural keys unique per path.
 *
 * <p>{@code @SoftUk(name = "cart_natural_key_per_tenant", fields = {"tenantId", "userId"})} — solves
 * NFR-IDEM-3 idempotency at the application layer. The {@code UkValidator} fires in
 * {@code GetOrCreateCartUseCase.getOrCreate(...)} before save (see Story 2.1 AC #8). Anonymous carts
 * ({@code userId == null}) bypass the user-bound key check: {@code UkValidator} skips UK groups that
 * contain a null field, so the anonymous path is covered only by the DB partial index
 * {@code uq_carts_tenant_guest}.
 *
 * <p>{@code @Version version} — Hibernate optimistic concurrency (FR-16). Concurrent edits raise
 * {@code ObjectOptimisticLockingFailureException}, rethrown as {@code CartVersionConflictException}
 * → HTTP 409 with the latest state.
 */
@Entity
@Table(name = "carts")
@SoftUk(name = "cart_natural_key_per_tenant", fields = {"tenantId", "userId"})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class Cart extends BaseEntity {

  /** Single-tenant default ({@code "default"}) — architecture-detail.md line 78. */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /** Cookie UUID for anonymous carts; {@code null} for user-bound carts. */
  @Column(name = "guest_cart_id", length = 64)
  private String guestCartId;

  /** Auth user id for user-bound carts; {@code null} for anonymous carts. */
  @Column(name = "user_id", length = 64)
  private String userId;

  /** Lifecycle status — see {@link CartStatus}. {@code @Enumerated(STRING)} → VARCHAR(32). */
  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private CartStatus status;

  /** Optimistic-concurrency version (FR-16). Incremented by Hibernate on every mutation. */
  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /** Defaults {@code status = ANONYMOUS} + {@code tenantId = "default"} at insert time. */
  @PrePersist
  public void onPrePersist() {
    if (status == null) {
      status = CartStatus.ANONYMOUS;
    }
    if (tenantId == null) {
      tenantId = "default";
    }
  }
}
