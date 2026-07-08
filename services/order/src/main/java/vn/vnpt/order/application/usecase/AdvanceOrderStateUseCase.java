package vn.vnpt.order.application.usecase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Smoke driver for the order state machine — Story 4.2. Advances the order to a target state
 * via the existing {@link AppendOrderTransitionUseCase}. Real consumer path is the
 * {@code @ApplicationModuleListener} for {@code payment.captured} + future events.
 */
@Service
@Transactional
public class AdvanceOrderStateUseCase {

  private final AppendOrderTransitionUseCase appendUseCase;
  private final OrderPriceSnapshotRepository priceSnapshotRepository;

  public AdvanceOrderStateUseCase(
      AppendOrderTransitionUseCase appendUseCase,
      OrderPriceSnapshotRepository priceSnapshotRepository) {
    this.appendUseCase = appendUseCase;
    this.priceSnapshotRepository = priceSnapshotRepository;
  }

  public long execute(long orderUuid, OrderState targetState, String sagaStep) {
    OrderPriceSnapshot snapshot = priceSnapshotRepository.findById(orderUuid)
        .orElseThrow(() -> new IllegalStateException(
            "Cannot advance: order price snapshot missing for orderUuid=" + orderUuid));
    return appendUseCase.execute(new AppendOrderTransitionCommand(
        orderUuid, targetState, sagaStep, snapshot));
  }

  /** Helper for tests that need a fresh saga-step tag. */
  public static String freshSagaStep(String prefix) {
    return prefix + "." + (SnowflakeIdGenerator.generateId() % 1_000_000);
  }
}