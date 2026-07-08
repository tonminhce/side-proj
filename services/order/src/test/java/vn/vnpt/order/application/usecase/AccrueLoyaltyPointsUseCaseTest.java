package vn.vnpt.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccrualEntity;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccountEntity;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccountRepository;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccrualRepository;

class AccrueLoyaltyPointsUseCaseTest {

  private LoyaltyAccountRepository accountRepo;
  private LoyaltyAccrualRepository accrualRepo;
  private AccrueLoyaltyPointsUseCase useCase;

  @BeforeEach
  void setUp() {
    accountRepo = Mockito.mock(LoyaltyAccountRepository.class);
    accrualRepo = Mockito.mock(LoyaltyAccrualRepository.class);
    useCase = new AccrueLoyaltyPointsUseCase(accountRepo, accrualRepo);
  }

  @Test
  void accrue_floorTotalCentsDividedBy100() {
    Mockito.when(accrualRepo.findByOrderUuid(42L)).thenReturn(Optional.empty());
    Mockito.when(accountRepo.findByCustomerId(99L)).thenReturn(Optional.of(
        LoyaltyAccountEntity.builder().id(1L).customerId(99L).points(0L)
            .updatedAt(LocalDateTime.now()).build()));
    Mockito.when(accountRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // totalCents=10000 → 10000/100 = 100 points
    int points = useCase.execute(42L, 99L, 10000L);

    assertThat(points).isEqualTo(100);
  }

  @Test
  void accrue_createsAccountIfMissing() {
    Mockito.when(accrualRepo.findByOrderUuid(42L)).thenReturn(Optional.empty());
    Mockito.when(accountRepo.findByCustomerId(99L)).thenReturn(Optional.empty());
    Mockito.when(accountRepo.save(any())).thenAnswer(inv -> {
      LoyaltyAccountEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(1L);
      return e;
    });

    int points = useCase.execute(42L, 99L, 5000L);

    assertThat(points).isEqualTo(50);
    // Verify save was called for the account creation
    Mockito.verify(accountRepo, Mockito.times(2)).save(any(LoyaltyAccountEntity.class));
  }

  @Test
  void accrue_idempotentForDuplicateOrder() {
    Mockito.when(accrualRepo.findByOrderUuid(42L)).thenReturn(Optional.of(
        LoyaltyAccrualEntity.builder().id(1L).orderUuid(42L).customerId(99L)
            .points(100).createdAt(LocalDateTime.now()).build()));

    int points = useCase.execute(42L, 99L, 10000L);

    // Returns 0; no save, no double-accrual.
    assertThat(points).isEqualTo(0);
    Mockito.verify(accountRepo, Mockito.never()).save(any());
  }
}