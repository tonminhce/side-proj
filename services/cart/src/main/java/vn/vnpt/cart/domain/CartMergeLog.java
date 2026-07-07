package vn.vnpt.cart.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.vnpt.cart.domain.annotation.IgnoreSoftUkAudit;
import vn.vnpt.util.common.entity.base.BaseEntity;

/**
 * CartMergeLog — append-only audit trail + idempotency beacon (Story 2.1 / NFR-IDEM-3, ADR-11).
 *
 * <p>{@code @IgnoreSoftUkAudit} — append-only audit log; the natural key ({@code idempotency_key})
 * is enforced by the DB UNIQUE constraint {@code uq_cart_merge_log_idempotency_key} on
 * {@code V001__create_cart_tables.sql}; a {@code @SoftUk} would be redundant. Merge log rows are
 * NEVER updated or soft-deleted (the merge endpoint only inserts). No {@code @Version} (append-only).
 */
@Entity
@Table(name = "cart_merge_log")
@IgnoreSoftUkAudit
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class CartMergeLog extends BaseEntity {

  /** Single-tenant default ({@code "default"}). */
  @Column(name = "tenant_id", nullable = false, length = 64)
  private String tenantId;

  /** {@code sha256(guestCartId + ":" + userId)} hex — the ADR-11 idempotency key (DB UNIQUE). */
  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "guest_cart_id", nullable = false, length = 64)
  private String guestCartId;

  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  /** The anonymous cart that was merged. */
  @Column(name = "source_cart_uuid")
  private Long sourceCartUuid;

  /** The user-bound cart that received the lines. */
  @Column(name = "target_cart_uuid")
  private Long targetCartUuid;

  @Column(name = "merged_lines_count", nullable = false)
  private Integer mergedLinesCount;

  @Column(name = "merged_at", nullable = false)
  private Instant mergedAt;

  /** Defaults {@code tenantId = "default"} + {@code mergedAt = now} at insert time. */
  @PrePersist
  public void onPrePersist() {
    if (tenantId == null) {
      tenantId = "default";
    }
    if (mergedAt == null) {
      mergedAt = Instant.now();
    }
    if (mergedLinesCount == null) {
      mergedLinesCount = 0;
    }
  }
}
