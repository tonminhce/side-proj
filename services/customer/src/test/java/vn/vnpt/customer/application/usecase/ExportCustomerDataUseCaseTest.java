package vn.vnpt.customer.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.customer.application.port.ExportResponse;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.AddressRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

class ExportCustomerDataUseCaseTest {

  private CustomerRepository customerRepo;
  private AddressRepository addressRepo;
  private ExportCustomerDataUseCase useCase;
  private Clock fixedClock;

  @BeforeEach
  void setUp() {
    customerRepo = Mockito.mock(CustomerRepository.class);
    addressRepo = Mockito.mock(AddressRepository.class);
    fixedClock = Clock.fixed(Instant.parse("2026-07-08T03:00:00Z"), ZoneOffset.UTC);
    useCase = new ExportCustomerDataUseCase(customerRepo, addressRepo, fixedClock);
  }

  @Test
  void execute_returnsCustomerWithAddresses() {
    CustomerEntity customer = CustomerEntity.builder()
        .id(1L).userId(99L).displayName("Nguyen Van A")
        .email("a@x.vn").phone("0901").build();
    AddressEntity addr = AddressEntity.builder()
        .id(10L).customer(customer).line1("123 Main St")
        .provinceCode("01").districtCode("001").communeCode("00001")
        .isDefault(false).build();
    Mockito.when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));
    Mockito.when(addressRepo.findByCustomerId(1L)).thenReturn(List.of(addr));

    ExportResponse result = useCase.execute(1L);

    assertThat(result.customerId()).isEqualTo(1L);
    assertThat(result.userId()).isEqualTo(99L);
    assertThat(result.displayName()).isEqualTo("Nguyen Van A");
    assertThat(result.addresses()).hasSize(1);
  }

  @Test
  void execute_throwsOnUnknownCustomer() {
    Mockito.when(customerRepo.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.execute(999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Customer not found");
  }

  @Test
  void exportedAt_isCurrentInstant() {
    CustomerEntity customer = CustomerEntity.builder()
        .id(1L).userId(99L).displayName("Nguyen Van A").build();
    Mockito.when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));
    Mockito.when(addressRepo.findByCustomerId(1L)).thenReturn(List.of());

    ExportResponse result = useCase.execute(1L);

    assertThat(result.exportedAt()).isEqualTo(Instant.parse("2026-07-08T03:00:00Z"));
  }
}