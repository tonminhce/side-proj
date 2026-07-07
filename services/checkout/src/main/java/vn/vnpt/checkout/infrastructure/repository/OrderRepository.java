package vn.vnpt.checkout.infrastructure.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.vnpt.checkout.domain.Order;

/**
 * Spring Data JPA repository for {@link Order} — Story 2.5 / FR-22, FR-23.
 *
 * <p>NO {@code void delete*(...)} methods: order rows transition {@code status} (FSM), never
 * row-deleted. Enforced by {@code CheckoutPackageBoundaryTest}.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

  Optional<Order> findByUuid(Long uuid);

  Optional<Order> findByCartUuid(Long cartUuid);

  /**
   * Saga idempotency lookup — every {@code CheckoutStartedEvent} for the same {@code checkoutUuid}
   * must yield zero observable effect after the first successful run.
   */
  Optional<Order> findByCheckoutUuid(Long checkoutUuid);

  /**
   * Recovery query — orders in any of the in-flight statuses ({@code CREATED}, {@code STOCK_RESERVED},
   * {@code PAYMENT_PENDING}) whose {@code updated_at} is older than {@code cutoff} (default 5 min).
   * Backs the partial index {@code idx_orders_inflight_updated} from V003.
   */
  @Query("select o from Order o where o.status in :statuses and o.updatedAt < :cutoff order by o.updatedAt asc")
  List<Order> findStuckOrders(@Param("statuses") List<vn.vnpt.checkout.domain.OrderStatus> statuses,
      @Param("cutoff") LocalDateTime cutoff);
}