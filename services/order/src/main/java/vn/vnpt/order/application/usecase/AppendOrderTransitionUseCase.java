package vn.vnpt.order.application.usecase;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
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
    if (latest.isPresent()
        && cmd.toState().name().equals(latest.get().getToState())
        && cmd.sagaStep().equals(latest.get().getSagaStep())) {
      return latest.get().getId();
    }
    OrderState fromState = latest.map(t -> OrderState.valueOf(t.getToState())).orElse(null);

    if (!validator.isAllowed(fromState, cmd.toState())) {
      throw new IllegalStateException(
          "Invalid transition: " + (fromState == null ? "null" : fromState)
              + " -> " + cmd.toState() + " (allowed: "
              + (fromState == null ? "PLACED" : validator.isAllowed(fromState, cmd.toState()))
              + ")");
    }

    // Insert price snapshot only on the first transition (FR-31 immutability).
    // A concurrent genesis retry (e.g. client retry on 5xx) may race: both calls reach this
    // point with fromState == null; the second save() collides on the snapshot's natural PK and
    // would throw DataIntegrityViolationException without this guard. The snapshot data is
    // identical in both calls (it's part of the command), so swallowing the duplicate and
    // proceeding to the transition write is correct — the V005 unique index will catch the
    // duplicate transition itself.
    if (fromState == null) {
      try {
        priceSnapshotRepository.saveAndFlush(cmd.priceSnapshot());
      } catch (DataIntegrityViolationException e) {
        // Concurrent genesis — the winning thread already inserted the same snapshot. Safe to
        // fall through; the V005 transition unique index will catch a true duplicate below.
        if (priceSnapshotRepository.findById(cmd.orderUuid()).isEmpty()) {
          throw e;
        }
      }
    }

    OrderStateTransition transition = OrderStateTransition.builder()
        .orderUuid(cmd.orderUuid())
        .fromState(fromState == null ? null : fromState.name())
        .toState(cmd.toState().name())
        .sagaStep(cmd.sagaStep())
        .eventId(SnowflakeIdGenerator.generateId())
        .createdAt(LocalDateTime.now())
        .build();
    OrderStateTransition saved = transitionRepository.saveAndFlush(transition);
    appender.append(saved);
    return saved.getId();
  }
}
