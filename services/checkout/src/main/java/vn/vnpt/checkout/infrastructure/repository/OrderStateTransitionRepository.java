package vn.vnpt.checkout.infrastructure.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.checkout.domain.OrderStateTransition;

/**
 * Spring Data JPA repository for {@link OrderStateTransition} — Story 2.5 / FR-22, FR-23.
 *
 * <p>Append-only by application convention: only {@code save} (insert) and read methods are exposed;
 * no {@code update} / {@code delete}. The recovery runner replays by reading the latest row for an
 * order; the orchestrator appends the next row via {@code save}.
 */
public interface OrderStateTransitionRepository extends JpaRepository<OrderStateTransition, Long> {

  /** Replay-by-order — used by the recovery runner. */
  List<OrderStateTransition> findByOrderUuidOrderByCreatedAtAsc(Long orderUuid);

  /** Convenience — last transition (saga step that drove the current state). */
  default OrderStateTransition findFirstByOrderUuidOrderByCreatedAtDesc(Long orderUuid) {
    List<OrderStateTransition> rows = findByOrderUuidOrderByCreatedAtAsc(orderUuid);
    return rows.isEmpty() ? null : rows.get(rows.size() - 1);
  }
}