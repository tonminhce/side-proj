package vn.vnpt.customer.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.vnpt.customer.application.port.ForgetResponse;
import vn.vnpt.customer.infrastructure.entity.CustomerDataRegistryEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.CustomerDataRegistryRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

class ForgetCustomerUseCaseTest {

  private CustomerRepository customerRepo;
  private CustomerDataRegistryRepository registryRepo;
  private ForgetCustomerUseCase useCase;
  private Clock fixedClock;

  @BeforeEach
  void setUp() {
    customerRepo = Mockito.mock(CustomerRepository.class);
    registryRepo = Mockito.mock(CustomerDataRegistryRepository.class);
    fixedClock = Clock.fixed(Instant.parse("2026-07-08T03:00:00Z"), ZoneOffset.UTC);
    useCase = new ForgetCustomerUseCase(customerRepo, registryRepo, fixedClock);
  }

  @Test
  void execute_deletesCustomerAndInsertsAudit() {
    CustomerEntity customer = CustomerEntity.builder().id(1L).userId(99L).build();
    Mockito.when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));

    ForgetResponse result = useCase.execute(1L);

    assertThat(result.customerId()).isEqualTo(1L);
    assertThat(result.forgottenAt()).isEqualTo(Instant.parse("2026-07-08T03:00:00Z"));
    Mockito.verify(customerRepo).delete(customer);
    Mockito.verify(registryRepo).save(any(CustomerDataRegistryEntity.class));
  }

  @Test
  void execute_throwsOnUnknownCustomer() {
    Mockito.when(customerRepo.findById(999L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> useCase.execute(999L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Customer not found");
  }

  @Test
  void execute_cascadeDeletesAddresses() {
    CustomerEntity customer = CustomerEntity.builder().id(1L).userId(99L).build();
    Mockito.when(customerRepo.findById(1L)).thenReturn(Optional.of(customer));

    useCase.execute(1L);

    // The cascade is at the DB level (V001 ON DELETE CASCADE); the use case just calls delete.
    // We assert the delete is invoked; the cascade is the DB's responsibility.
    Mockito.verify(customerRepo).delete(customer);
  }
}