package vn.vnpt.order.infrastructure.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccrualEntity;

public interface LoyaltyAccrualRepository extends JpaRepository<LoyaltyAccrualEntity, Long> {
  Optional<LoyaltyAccrualEntity> findByOrderUuid(Long orderUuid);
}