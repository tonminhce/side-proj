package vn.vnpt.customer.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.customer.application.port.ExportResponse;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.AddressRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

/** PDPD data export — Story 5.2 / FR-46. */
@Service
@Transactional(readOnly = true)
public class ExportCustomerDataUseCase {

  private final CustomerRepository customerRepository;
  private final AddressRepository addressRepository;
  private final Clock clock;

  public ExportCustomerDataUseCase(
      CustomerRepository customerRepository,
      AddressRepository addressRepository,
      Clock clock) {
    this.customerRepository = customerRepository;
    this.addressRepository = addressRepository;
    this.clock = clock;
  }

  public ExportResponse execute(long customerId) {
    CustomerEntity customer = customerRepository.findById(customerId)
        .orElseThrow(() -> new IllegalArgumentException("Customer not found: id=" + customerId));
    List<vn.vnpt.customer.infrastructure.entity.AddressEntity> addresses =
        addressRepository.findByCustomerId(customerId);
    return new ExportResponse(
        customer.getId(),
        customer.getUserId(),
        customer.getDisplayName(),
        customer.getEmail(),
        customer.getPhone(),
        addresses,
        Instant.now(clock));
  }
}