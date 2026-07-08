package vn.vnpt.order.application.usecase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccrualEntity;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccrualRepository;

@Service
@Transactional(readOnly = true)
public class GetLoyaltyForOrderUseCase {

  private final LoyaltyAccrualRepository accrualRepository;

  public GetLoyaltyForOrderUseCase(LoyaltyAccrualRepository accrualRepository) {
    this.accrualRepository = accrualRepository;
  }

  public LoyaltyAccrualEntity execute(long orderUuid) {
    return accrualRepository.findByOrderUuid(orderUuid)
        .orElseThrow(() -> new IllegalArgumentException("Loyalty accrual not found: orderUuid=" + orderUuid));
  }
}