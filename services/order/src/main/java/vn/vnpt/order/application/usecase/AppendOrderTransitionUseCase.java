package vn.vnpt.order.application.usecase;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.port.AppendOrderTransitionCommand;
import vn.vnpt.order.application.port.OrderTransitionAppender;
import vn.vnpt.order.domain.OrderState;
import vn.vnpt.order.domain.OrderTransitionValidator;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderPriceSnapshotRepository;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/**
 * Append an order state transition to the append-only log + insert the immutable price snapshot
 * on the first transition — Story 4.1 / FR-30, FR-31.
 *
 * <p>Append-only: the use case never calls {@code save()} on an existing entity. The first
 * transition for an order inserts the price snapshot; subsequent transitions keep the existing
 * snapshot (FR-31 immutability).
 *
 * <p>Uses {@link SnowflakeIdGenerator} for the event_id (mirrors the canonical pattern from
 * Story 1.3 / 3.2; the {@code event_id} UNIQUE constraint on the table is the dedup primitive).
 */
@Service
@Transactional
public class AppendOrderTransitionUseCase {

  private final OrderStateTransitionRepository transitionRepository;
  private final OrderPriceSnapshotRepository priceSnapshotRepository;
  private final OrderTransitionValidator validator;
  private final OrderTransitionAppender appender;

  public AppendOrderTransitionUseCase(
      OrderStateTransitionRepository transitionRepository,
      OrderPriceSnapshotRepository priceSnapshotRepository,
      OrderTransitionValidator validator,
      OrderTransitionAppender appender) {
    this.transitionRepository = transitionRepository;
    this.priceSnapshotRepository = priceSnapshotRepository;
    this.validator = validator;
    this.appender = appender;
  }

  public long execute(AppendOrderTransitionCommand cmd) {
    Optional<OrderStateTransition> latest =
        transitionRepository.findFirstByOrderUuidOrderByIdDesc(cmd.orderUuid());
    OrderState fromState = latest.map(t -> OrderState.valueOf(t.getToState())).orElse(null);

    if (!validator.isAllowed(fromState, cmd.toState())) {
      throw new IllegalStateException(
          "Invalid transition: " + (fromState == null ? "null" : fromState)
              + " -> " + cmd.toState() + " (allowed: "
              + (fromState == null ? "PLACED" : validator.isAllowed(fromState, cmd.toState()))
              + ")");
    }

    // Insert price snapshot only on the first transition (FR-31 immutability).
    if (fromState == null) {
      priceSnapshotRepository.save(cmd.priceSnapshot());
    }

    OrderStateTransition transition = OrderStateTransition.builder()
        .orderUuid(cmd.orderUuid())
        .fromState(fromState == null ? null : fromState.name())
        .toState(cmd.toState().name())
        .sagaStep(cmd.sagaStep())
        .eventId(SnowflakeIdGenerator.generateId())
        .createdAt(LocalDateTime.now())
        .build();
    OrderStateTransition saved = transitionRepository.save(transition);
    appender.append(saved);
    return saved.getId();
  }
}