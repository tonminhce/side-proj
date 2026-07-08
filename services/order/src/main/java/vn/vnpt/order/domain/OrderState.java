package vn.vnpt.order.domain;

/**
 * Order state machine — Story 4.1 + 4.2 + 4.4 + 3.5 follow-up #4.
 * Forward-compatible enum; Story 4.1 only emits {@link #PLACED}; subsequent states (PAID,
 * ALLOCATED, PACKING, PACKED, SHIPPED, DELIVERED) land in Story 4.2 (post-payment lifecycle).
 * Story 4.4 adds {@link #AMENDED} and {@link #CANCELLED} for the edit-after-pay contract (FR-34).
 * Story 3.5 follow-up #4 adds {@link #REFUNDED} for the payment.refunded saga listener.
 */
public enum OrderState {
  PLACED,
  PAID,
  ALLOCATED,
  PACKING,
  PACKED,
  SHIPPED,
  DELIVERED,
  AMENDED,
  CANCELLED,
  REFUNDED
}