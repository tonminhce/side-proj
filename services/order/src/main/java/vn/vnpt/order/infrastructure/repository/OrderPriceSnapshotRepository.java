package vn.vnpt.order.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.order.infrastructure.entity.OrderPriceSnapshot;

/**
 * Repository for the immutable {@code order_price_snapshot} — Story 4.1 / FR-31. INSERT-only
 * at the use-case layer; no {@code deleteById} / {@code deleteAll} API is exposed (the contract
 * is write-once).
 */
public interface OrderPriceSnapshotRepository extends JpaRepository<OrderPriceSnapshot, Long> {
}