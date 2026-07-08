package vn.vnpt.order.infrastructure.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccountEntity;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccountEntity, Long> {
  Optional<LoyaltyAccountEntity> findByCustomerId(Long customerId);
}