package vn.vnpt.customer.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.customer.application.port.AddAddressCommand;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.AddressRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

class AddAddressUseCaseTest {

  @Test
  void execute_persistsAddressLinkedToCustomer() {
    CustomerRepository customerRepo = Mockito.mock(CustomerRepository.class);
    AddressRepository addressRepo = Mockito.mock(AddressRepository.class);
    CustomerEntity customer = CustomerEntity.builder().id(7L).userId(99L).build();
    Mockito.when(customerRepo.findById(7L)).thenReturn(Optional.of(customer));
    Mockito.when(addressRepo.save(any())).thenAnswer(inv -> {
      AddressEntity a = inv.getArgument(0);
      if (a.getId() == null) a.setId(100L);
      return a;
    });
    AddAddressUseCase useCase = new AddAddressUseCase(customerRepo, addressRepo);

    long id = useCase.execute(new AddAddressCommand(7L, "123 Main St", "01", "001", "00001", true));

    assertThat(id).isEqualTo(100L);
    Mockito.verify(addressRepo).save(any(AddressEntity.class));
  }

  @Test
  void execute_throwsOnUnknownCustomer() {
    CustomerRepository customerRepo = Mockito.mock(CustomerRepository.class);
    AddressRepository addressRepo = Mockito.mock(AddressRepository.class);
    Mockito.when(customerRepo.findById(999L)).thenReturn(Optional.empty());
    AddAddressUseCase useCase = new AddAddressUseCase(customerRepo, addressRepo);

    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
        useCase.execute(new AddAddressCommand(999L, "x", "01", "001", "00001", false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Customer not found");
  }
}