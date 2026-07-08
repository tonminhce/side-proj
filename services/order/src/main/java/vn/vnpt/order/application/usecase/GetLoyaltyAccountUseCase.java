package vn.vnpt.order.application.usecase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccountEntity;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccountRepository;

@Service
@Transactional(readOnly = true)
public class GetLoyaltyAccountUseCase {

  private final LoyaltyAccountRepository accountRepository;

  public GetLoyaltyAccountUseCase(LoyaltyAccountRepository accountRepository) {
    this.accountRepository = accountRepository;
  }

  public LoyaltyAccountEntity execute(long customerId) {
    return accountRepository.findByCustomerId(customerId)
        .orElseThrow(() -> new IllegalArgumentException("Loyalty account not found: customerId=" + customerId));
  }
}