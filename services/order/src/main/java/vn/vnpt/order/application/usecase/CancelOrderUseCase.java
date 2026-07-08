package vn.vnpt.order.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.exception.OrderEditWindowClosedException;
import vn.vnpt.order.domain.exception.OrderTerminalStateException;
import vn.vnpt.order.domain.exception.OrderVersionMismatchException;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Cancel an order within the 30-minute edit window — Story 4.4 / FR-34.
 */
@Service
@Transactional
public class CancelOrderUseCase {

  private static final Duration EDIT_WINDOW = Duration.ofMinutes(30);
  private static final Set<OrderState> TERMINAL = Set.of(
      OrderState.CANCELLED, OrderState.SHIPPED, OrderState.DELIVERED);

  private final OrderStateTransitionRepository transitionRepository;
  private final OrderPriceSnapshotRepository priceSnapshotRepository;
  private final AppendOrderTransitionUseCase appendUseCase;
  private final Clock clock;

  public CancelOrderUseCase(
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository priceSnapshotRepository,
      AppendOrderTransitionUseCase appendUseCase,
      Clock clock) {
    this.transitionRepository = transitionRepository;
    this.priceSnapshotRepository = priceSnapshotRepository;
    this.appendUseCase = appendUseCase;
    this.clock = clock;
  }

  public long execute(long orderUuid, long expectedVersion) {
    OrderStateTransition genesis = transitionRepository
        .findFirstByOrderUuidOrderByIdAsc(orderUuid)
        .orElseThrow(() -> new IllegalStateException("Order not found: orderUuid=" + orderUuid));
    Instant closesAt = genesis.getCreatedAt().toInstant(java.time.ZoneOffset.UTC).plus(EDIT_WINDOW);
    if (Instant.now(clock).isAfter(closesAt)) {
      throw new OrderEditWindowClosedException(orderUuid, closesAt);
    }

    OrderState currentState = OrderState.valueOf(
        transitionRepository.findFirstByOrderUuidOrderByIdDesc(orderUuid)
            .orElseThrow().getToState());
    if (TERMINAL.contains(currentState)) {
      throw new OrderTerminalStateException(orderUuid, currentState);
    }

    long currentVersion = transitionRepository.findByOrderUuidOrderByIdAsc(orderUuid).size();
    if (currentVersion != expectedVersion) {
      throw new OrderVersionMismatchException(orderUuid, expectedVersion, currentVersion);
    }

    OrderPriceSnapshot snapshot = priceSnapshotRepository.findById(orderUuid)
        .orElseThrow(() -> new IllegalStateException("Snapshot missing: orderUuid=" + orderUuid));
    // FR-31: snapshot price data is NOT modified on cancel; only the AMENDED transition is appended.
    // (The new OrderState.CANCELLED is appended as the transition; the snapshot stays.)
    return appendUseCase.execute(new AppendOrderTransitionCommand(
        orderUuid, OrderState.CANCELLED, "order.cancelled", snapshot));
  }
}