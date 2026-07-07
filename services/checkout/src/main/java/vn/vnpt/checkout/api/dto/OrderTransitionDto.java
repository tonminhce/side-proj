package vn.vnpt.checkout.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;

/**
 * Read-only projection of {@link OrderStateTransition} for the
 * {@code GET /api/orders/by-checkout/{uuid}} response — Story 2.5 / FR-22.
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderTransitionDto {

  OrderStatus fromState;
  OrderStatus toState;
  String sagaStep;
  String failureReason;
  LocalDateTime occurredAt;

  public static OrderTransitionDto from(OrderStateTransition t) {
    return OrderTransitionDto.builder()
        .fromState(t.getFromState())
        .toState(t.getToState())
        .sagaStep(t.getSagaStep())
        .failureReason(t.getFailureReason())
        .occurredAt(t.getCreatedAt())
        .build();
  }
}