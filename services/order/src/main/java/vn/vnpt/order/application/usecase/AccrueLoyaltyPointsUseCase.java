package vn.vnpt.order.application.usecase;

import java.time.LocalDateTime;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccountEntity;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccrualEntity;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccountRepository;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccrualRepository;

/** Accrue loyalty points when an order is PAID — Story 5.6 / FR-50.
 *  Formula: {@code points = floor(totalCents * 0.01)}. Idempotent on orderUuid (the accrual table
 *  has a UNIQUE constraint on order_uuid).
 *
 *  <p>Optimistic-lock safe: catches {@link ObjectOptimisticLockingFailureException} once and
 *  retries — the accrual row UNIQUE(order_uuid) constraint prevents double-application even
 *  if the retry surfaces the same race.
 *  ponytail: single inline retry, not a retry util — add a Spring Retry annotation when
 *  contention metrics justify it. */
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

  public long execute(long orderUuid, long customerId, long totalCents) {
    // Idempotency: if an accrual row already exists for this order, skip — fast path.
    if (accrualRepository.findByOrderUuid(orderUuid).isPresent()) {
      return 0L;
    }
    long points = totalCents / 100;  // V008 widened to long (no overflow on realistic order totals)
    if (points == 0) {
      return 0L;
    }
    try {
      return applyOnce(orderUuid, customerId, points);
    } catch (ObjectOptimisticLockingFailureException e) {
      // Concurrent writer raced us; re-read + retry. Accrual UNIQUE(order_uuid) means a second
      // writer also racing will fall into the fast-path idempotency check on the next read.
      return applyOnce(orderUuid, customerId, points);
    }
  }

  private long applyOnce(long orderUuid, long customerId, long points) {
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