package vn.vnpt.order.application.port;

import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;

/**
 * Command for appending a state transition to an order — Story 4.1 / FR-30, FR-31.
 * Trust-boundary validation lives in the compact constructor.
 *
 * <p>Story 5.6 AC #2: {@code pointsApplied} is the loyalty points redeemed on this transition
 * (nullable — null means no redemption). Order effective total = totalCents - pointsApplied.
 * The full redemption flow (RedeemLoyaltyPointsUseCase) is a follow-up story; for now this
 * field is plumbed through and ignored by the append-only log.
 */
public record AppendOrderTransitionCommand(
    long orderUuid,
    OrderState toState,
    String sagaStep,
    OrderPriceSnapshot priceSnapshot,
    Long pointsApplied) {

  /** Backward-compat ctor — defaults pointsApplied to null. */
  public AppendOrderTransitionCommand(long orderUuid, OrderState toState, String sagaStep,
                                       OrderPriceSnapshot priceSnapshot) {
    this(orderUuid, toState, sagaStep, priceSnapshot, null);
  }

  public AppendOrderTransitionCommand {
    if (toState == null) {
      throw new IllegalArgumentException("toState must not be null");
    }
    if (sagaStep == null || sagaStep.isBlank()) {
      throw new IllegalArgumentException("sagaStep must not be null or blank");
    }
    if (priceSnapshot == null) {
      throw new IllegalArgumentException("priceSnapshot must not be null (FR-31 captures at order.placed)");
    }
    if (pointsApplied != null && pointsApplied < 0) {
      throw new IllegalArgumentException("pointsApplied must be non-negative (was " + pointsApplied + ")");
    }
  }
}