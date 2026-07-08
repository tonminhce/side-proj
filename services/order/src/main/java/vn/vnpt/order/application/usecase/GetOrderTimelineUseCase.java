package vn.vnpt.order.application.usecase;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.application.web.OrderTimelineEntry;
import vn.vnpt.order.application.web.OrderTimelineResponse;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;
import vn.vnpt.order.infrastructure.repository.OrderStateTransitionRepository;

/**
 * Get the order timeline — Story 4.3 / FR-33. Pure projection of the append-only log.
 * Returns HTTP 200 with empty array for unknown orders (per the BFF contract).
 */
@Service
@Transactional(readOnly = true)
public class GetOrderTimelineUseCase {

  private final OrderStateTransitionRepository transitionRepository;

  public GetOrderTimelineUseCase(OrderStateTransitionRepository transitionRepository) {
    this.transitionRepository = transitionRepository;
  }

  public OrderTimelineResponse execute(long orderUuid) {
    List<OrderStateTransition> transitions =
        transitionRepository.findByOrderUuidOrderByIdAsc(orderUuid);
    List<OrderTimelineEntry> entries = transitions.stream()
        .map(t -> new OrderTimelineEntry(
            t.getToState(),
            t.getCreatedAt().toInstant(ZoneOffset.UTC)))
        .toList();
    return new OrderTimelineResponse(orderUuid, entries);
  }
}