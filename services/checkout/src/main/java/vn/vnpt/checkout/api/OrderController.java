package vn.vnpt.checkout.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.checkout.api.dto.OrderResponse;
import vn.vnpt.checkout.domain.Order;
import vn.vnpt.checkout.domain.OrderStateTransition;
import vn.vnpt.checkout.domain.exception.OrderNotFoundException;
import vn.vnpt.checkout.infrastructure.repository.OrderRepository;
import vn.vnpt.checkout.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Order read API — Story 2.5 / FR-22 (storefront polling on saga state).
 *
 * <p>{@code GET /api/orders/by-checkout/{checkoutUuid}} returns the {@link Order} aggregate + its
 * full transition log. The storefront polls this endpoint to learn whether the saga has reached
 * {@code PAYMENT_PENDING}, transitioned to {@code FAILED}, etc.
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

  private final OrderRepository orderRepository;
  private final OrderStateTransitionRepository transitionRepository;

  @GetMapping("/by-checkout/{checkoutUuid}")
  @Transactional(readOnly = true)
  public ResponseEntity<OrderResponse> findByCheckout(@PathVariable Long checkoutUuid) {
    Order order = orderRepository.findByCheckoutUuid(checkoutUuid)
        .orElseThrow(() -> new OrderNotFoundException(checkoutUuid));
    var transitions = transitionRepository.findByOrderUuidOrderByCreatedAtAsc(order.getUuid());
    return ResponseEntity.ok(OrderResponse.from(order, transitions));
  }
}