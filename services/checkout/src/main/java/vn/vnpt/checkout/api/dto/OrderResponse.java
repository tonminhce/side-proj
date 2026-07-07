package vn.vnpt.checkout.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.OrderStatus;

/**
 * Read-only DTO for {@code GET /api/orders/by-checkout/{uuid}} — Story 2.5 / FR-22.
 *
 * <p>Includes the order's FSM state + version + the full transition log (append-only).
 */
@Value
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderResponse {

  Long orderUuid;
  Long cartUuid;
  Long checkoutUuid;
  String paymentIntentId;
  OrderStatus status;
  Long version;
  String failureReason;
  String failureSagaStep;
  LocalDateTime createdAt;
  LocalDateTime updatedAt;
  List<OrderTransitionDto> transitions;

  public static OrderResponse from(Order order, List<OrderStateTransition> transitions) {
    return OrderResponse.builder()
        .orderUuid(order.getUuid())
        .cartUuid(order.getCartUuid())
        .checkoutUuid(order.getCheckoutUuid())
        .paymentIntentId(order.getPaymentIntentId())
        .status(order.getStatus())
        .version(order.getVersion())
        .failureReason(order.getFailureReason())
        .failureSagaStep(order.getFailureSagaStep())
        .createdAt(order.getCreatedAt())
        .updatedAt(order.getUpdatedAt())
        .transitions(transitions == null ? List.of()
            : transitions.stream().map(OrderTransitionDto::from).toList())
        .build();
  }
}