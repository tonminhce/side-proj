package vn.vnpt.customer.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.customer.application.port.CreateCustomerCommand;
import vn.vnpt.customer.domain.Customer;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

class CreateCustomerUseCaseTest {

  @Test
  void execute_persistsCustomerWithGeneratedId() {
    CustomerRepository repo = Mockito.mock(CustomerRepository.class);
    Mockito.when(repo.save(any())).thenAnswer(inv -> {
      CustomerEntity e = inv.getArgument(0);
      if (e.getId() == null) e.setId(1L);
      return e;
    });
    CreateCustomerUseCase useCase = new CreateCustomerUseCase(repo);

    Customer result = useCase.execute(new CreateCustomerCommand(99L, "Nguyen Van A", "a@x.vn", "0901"));

    assertThat(result.id()).isEqualTo(1L);
    assertThat(result.userId()).isEqualTo(99L);
    assertThat(result.displayName()).isEqualTo("Nguyen Van A");
    assertThat(result.email()).isEqualTo("a@x.vn");
    assertThat(result.phone()).isEqualTo("0901");
  }

  @Test
  void execute_throwsOnNullUserId() {
    assertThatThrownBy(() -> new CreateCustomerCommand(0L, "x", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("userId");
  }

  @Test
  void execute_throwsOnNullDisplayName() {
    assertThatThrownBy(() -> new CreateCustomerCommand(1L, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("displayName");
  }
}