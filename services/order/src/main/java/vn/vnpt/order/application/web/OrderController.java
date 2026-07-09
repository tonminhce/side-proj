package vn.vnpt.order.application.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.application.usecase.AccrueLoyaltyPointsUseCase;
import vn.vnpt.order.application.usecase.AdvanceOrderStateUseCase;
import vn.vnpt.order.application.usecase.AmendOrderAddressUseCase;
import vn.vnpt.order.application.usecase.AppendOrderTransitionUseCase;
import vn.vnpt.order.application.usecase.CancelOrderUseCase;
import vn.vnpt.order.application.usecase.GetLoyaltyAccountUseCase;
import vn.vnpt.order.application.usecase.GetLoyaltyForOrderUseCase;
import vn.vnpt.order.application.usecase.GetOrderTimelineUseCase;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.exception.OrderSnapshotMissingException;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Order controller — Story 4.1 + 4.2 + 4.3 + 4.4. Endpoints:
 *   - POST /api/orders (append transition; smoke + saga)
 *   - POST /api/orders/{id}/advance?targetState=… (smoke driver)
 *   - GET  /api/orders/{id} (raw history)
 *   - GET  /api/orders/{id}/timeline (FR-33 with Cache-Control 30s)
 *   - POST /api/orders/{id}/address (FR-34 amend within 30-min window)
 *   - POST /api/orders/{id}/cancel (FR-34 cancel within 30-min window)
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final AppendOrderTransitionUseCase appendUseCase;
  private final AdvanceOrderStateUseCase advanceUseCase;
  private final AmendOrderAddressUseCase amendUseCase;
  private final CancelOrderUseCase cancelUseCase;
  private final GetOrderTimelineUseCase getTimelineUseCase;
  private final GetLoyaltyAccountUseCase getLoyaltyAccountUseCase;
  private final GetLoyaltyForOrderUseCase getLoyaltyForOrderUseCase;
  private final AccrueLoyaltyPointsUseCase accrueLoyaltyPointsUseCase;
  private final OrderStateTransitionRepository transitionRepository;
  private final OrderPriceSnapshotRepository snapshotRepository;

  public OrderController(
      AppendOrderTransitionUseCase appendUseCase,
      AdvanceOrderStateUseCase advanceUseCase,
      AmendOrderAddressUseCase amendUseCase,
      CancelOrderUseCase cancelUseCase,
      GetOrderTimelineUseCase getTimelineUseCase,
      GetLoyaltyAccountUseCase getLoyaltyAccountUseCase,
      GetLoyaltyForOrderUseCase getLoyaltyForOrderUseCase,
      AccrueLoyaltyPointsUseCase accrueLoyaltyPointsUseCase,
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository snapshotRepository) {
    this.appendUseCase = appendUseCase;
    this.advanceUseCase = advanceUseCase;
    this.amendUseCase = amendUseCase;
    this.cancelUseCase = cancelUseCase;
    this.getTimelineUseCase = getTimelineUseCase;
    this.getLoyaltyAccountUseCase = getLoyaltyAccountUseCase;
    this.getLoyaltyForOrderUseCase = getLoyaltyForOrderUseCase;
    this.accrueLoyaltyPointsUseCase = accrueLoyaltyPointsUseCase;
    this.transitionRepository = transitionRepository;
    this.snapshotRepository = snapshotRepository;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> append(@RequestBody AppendOrderTransitionCommand cmd) {
    long id = appendUseCase.execute(cmd);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "transitionId", id,
        "orderUuid", cmd.orderUuid(),
        "toState", cmd.toState().name(),
        "sagaStep", cmd.sagaStep()));
  }

  @PostMapping("/{orderUuid}/advance")
  public ResponseEntity<Map<String, Object>> advance(
      @PathVariable long orderUuid,
      @RequestParam OrderState targetState) {
    long id = advanceUseCase.execute(orderUuid, targetState, "saga.advance." + targetState.name());
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "transitionId", id,
        "orderUuid", orderUuid,
        "toState", targetState.name()));
  }

  @GetMapping("/{orderUuid}")
  public List<OrderStateTransition> history(@PathVariable long orderUuid) {
    return transitionRepository.findByOrderUuidOrderByIdAsc(orderUuid);
  }

  /** Story 4.3 / FR-33. Returns 200 with empty array for unknown orders (per BFF contract). */
  @GetMapping("/{orderUuid}/timeline")
  public ResponseEntity<OrderTimelineResponse> timeline(@PathVariable long orderUuid) {
    OrderTimelineResponse body = getTimelineUseCase.execute(orderUuid);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS).cachePublic())
        .header("Vary", "Accept-Encoding")
        .body(body);
  }

  /** Story 4.4 / FR-34. Amend the address within the 30-min window. */
  @PostMapping("/{orderUuid}/address")
  public ResponseEntity<Map<String, Object>> amendAddress(
      @PathVariable long orderUuid,
      @RequestBody AmendAddressRequest body) {
    long id = amendUseCase.execute(orderUuid, body.version(), body.address());
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "transitionId", id,
        "orderUuid", orderUuid,
        "toState", "AMENDED"));
  }

  /** Story 4.4 / FR-34. Cancel the order within the 30-min window. */
  @PostMapping("/{orderUuid}/cancel")
  public ResponseEntity<Map<String, Object>> cancel(
      @PathVariable long orderUuid,
      @RequestBody CancelRequest body) {
    long id = cancelUseCase.execute(orderUuid, body.version());
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "transitionId", id,
        "orderUuid", orderUuid,
        "toState", "CANCELLED"));
  }

  public record AmendAddressRequest(long version, String address) {}
  public record CancelRequest(long version, String reason) {}

  /** Story 5.6 / FR-50 — loyalty points for a specific order. */
  @GetMapping("/{orderUuid}/loyalty")
  public ResponseEntity<Map<String, Object>> loyaltyForOrder(@PathVariable long orderUuid) {
    var accrual = getLoyaltyForOrderUseCase.execute(orderUuid);
    return ResponseEntity.ok(Map.of(
        "orderUuid", orderUuid,
        "points", accrual.getPoints(),
        "createdAt", accrual.getCreatedAt().toString()));
  }

  /** Story 5.6 / FR-50 — total loyalty points for a customer. */
  @GetMapping("/customer/{customerId}/loyalty-account")
  public ResponseEntity<Map<String, Object>> loyaltyAccount(@PathVariable long customerId) {
    var account = getLoyaltyAccountUseCase.execute(customerId);
    return ResponseEntity.ok(Map.of(
        "customerId", customerId,
        "points", account.getPoints(),
        "updatedAt", account.getUpdatedAt().toString()));
  }

  /** Story 5.6 / FR-50 — internal accrual hook (called by the saga on PAID).
   *  Trust boundary fix: totalCents now comes from the immutable order_price_snapshot
   *  (FR-31 contract) rather than the request body. customerId is supplied by the saga
   *  (the only legitimate caller); unknown orders → 404. The accrual row's
   *  UNIQUE(order_uuid) prevents re-fire even if this endpoint is called twice.
   *  ponytail: snapshot is the source of truth — upgrade to drop customerId too once
   *  OrderPriceSnapshot.user_id lands (Story 5.6 follow-up). */
  @PostMapping("/{orderUuid}/accrue-loyalty")
  public ResponseEntity<Map<String, Object>> accrueLoyalty(
      @PathVariable long orderUuid,
      @RequestParam long customerId) {
    var snapshot = snapshotRepository.findById(orderUuid)
        .orElseThrow(() -> new OrderSnapshotMissingException(orderUuid));
    long points = accrueLoyaltyPointsUseCase.execute(orderUuid, customerId, snapshot.getTotalCents());
    return ResponseEntity.ok(Map.of(
        "orderUuid", orderUuid,
        "customerId", customerId,
        "totalCents", snapshot.getTotalCents(),
        "points", points));
  }
}