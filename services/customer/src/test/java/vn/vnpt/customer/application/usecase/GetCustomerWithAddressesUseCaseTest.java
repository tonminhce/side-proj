package vn.vnpt.customer.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.customer.domain.Customer;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.AddressRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

class GetCustomerWithAddressesUseCaseTest {

  @Test
  void execute_returnsCustomerWithAddresses() {
    CustomerRepository customerRepo = Mockito.mock(CustomerRepository.class);
    AddressRepository addressRepo = Mockito.mock(AddressRepository.class);
    CustomerEntity customer = CustomerEntity.builder()
        .id(1L).userId(99L).displayName("Nguyen Van A").build();
    AddressEntity addr = AddressEntity.builder()
        .id(10L).customer(customer).line1("123 Main St")
        .provinceCode("01").districtCode("001").communeCode("00001")
        .isDefault(false).build();
    Mockito.when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));
    Mockito.when(addressRepo.findByCustomerId(1L)).thenReturn(List.of(addr));
    GetCustomerWithAddressesUseCase useCase = new GetCustomerWithAddressesUseCase(customerRepo, addressRepo);

    Customer result = useCase.execute(1L);

    assertThat(result.id()).isEqualTo(1L);
    assertThat(result.addresses()).hasSize(1);
    assertThat(result.addresses().get(0).getProvinceCode()).isEqualTo("01");
  }

  @Test
  void execute_throwsOnUnknownCustomer() {
    CustomerRepository customerRepo = Mockito.mock(CustomerRepository.class);
    AddressRepository addressRepo = Mockito.mock(AddressRepository.class);
    Mockito.when(customerRepo.findById(999L)).thenReturn(Optional.empty());
    GetCustomerWithAddressesUseCase useCase = new GetCustomerWithAddressesUseCase(customerRepo, addressRepo);

    assertThatThrownBy(() -> useCase.execute(999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Customer not found");
  }
}