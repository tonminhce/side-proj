package vn.vnpt.order.application.usecase;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccountEntity;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccrualEntity;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccountRepository;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccrualRepository;

/** Accrue loyalty points when an order is PAID — Story 5.6 / FR-50.
 *  Formula: {@code points = floor(totalCents * 0.01)}. Idempotent on orderUuid (the accrual table
 *  has a UNIQUE constraint on order_uuid). */
@Service
@Transactional
public class AccrueLoyaltyPointsUseCase {

  private final LoyaltyAccountRepository accountRepository;
  private final LoyaltyAccrualRepository accrualRepository;

  public AccrueLoyaltyPointsUseCase(LoyaltyAccountRepository accountRepository,
                                     LoyaltyAccrualRepository accrualRepository) {
    this.accountRepository = accountRepository;
    this.accrualRepository = accrualRepository;
  }

  public int execute(long orderUuid, long customerId, long totalCents) {
    // Idempotency: if an accrual row already exists for this order, skip.
    if (accrualRepository.findByOrderUuid(orderUuid).isPresent()) {
      return 0;
    }
    int points = (int) (totalCents / 100);
    if (points == 0) {
      return 0;
    }

    LoyaltyAccountEntity account = accountRepository.findByCustomerId(customerId)
        .orElseGet(() -> accountRepository.save(LoyaltyAccountEntity.builder()
            .customerId(customerId)
            .points(0L)
            .updatedAt(LocalDateTime.now())
            .build()));
    account.setPoints(account.getPoints() + points);
    account.setUpdatedAt(LocalDateTime.now());
    accountRepository.save(account);

    accrualRepository.save(LoyaltyAccrualEntity.builder()
        .orderUuid(orderUuid)
        .customerId(customerId)
        .points(points)
        .createdAt(LocalDateTime.now())
        .build());
    return points;
  }
}