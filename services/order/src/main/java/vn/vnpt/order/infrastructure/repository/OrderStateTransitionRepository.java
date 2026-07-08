package vn.vnpt.order.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.order.infrastructure.entity.OrderStateTransition;

/**
 * Repository for the append-only {@code order_state_transition} log — Story 4.1 / FR-30.
 * INSERT-only at the use-case layer (no {@code save()} on existing entities; no
 * {@code deleteById} / {@code deleteAll} API is exposed — the contract is write-once).
 */
public interface OrderStateTransitionRepository extends JpaRepository<OrderStateTransition, Long> {

  Optional<OrderStateTransition> findFirstByOrderUuidOrderByIdDesc(Long orderUuid);

  Optional<OrderStateTransition> findFirstByOrderUuidOrderByIdAsc(Long orderUuid);

  List<OrderStateTransition> findByOrderUuidOrderByIdAsc(Long orderUuid);
}