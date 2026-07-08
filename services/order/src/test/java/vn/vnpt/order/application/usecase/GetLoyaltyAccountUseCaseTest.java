package vn.vnpt.order.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.order.infrastructure.entity.LoyaltyAccountEntity;
import vn.vnpt.order.infrastructure.repository.LoyaltyAccountRepository;

class GetLoyaltyAccountUseCaseTest {

  private LoyaltyAccountRepository repo;
  private GetLoyaltyAccountUseCase useCase;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(LoyaltyAccountRepository.class);
    useCase = new GetLoyaltyAccountUseCase(repo);
  }

  @Test
  void execute_returnsAccountForCustomer() {
    Mockito.when(repo.findByCustomerId(99L)).thenReturn(Optional.of(
        LoyaltyAccountEntity.builder().id(1L).customerId(99L).points(150L)
            .updatedAt(LocalDateTime.now()).build()));

    var account = useCase.execute(99L);

    assertThat(account.getPoints()).isEqualTo(150L);
  }

  @Test
  void execute_throwsOnUnknownCustomer() {
    Mockito.when(repo.findByCustomerId(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.execute(999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Loyalty account not found");
  }
}