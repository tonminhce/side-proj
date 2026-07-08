package vn.vnpt.customer.application.usecase;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.customer.domain.Customer;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.AddressRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

/**
 * Load a Customer with its addresses — Story 5.1.
 */
@Service
@Transactional(readOnly = true)
public class GetCustomerWithAddressesUseCase {

  private final CustomerRepository customerRepository;
  private final AddressRepository addressRepository;

  public GetCustomerWithAddressesUseCase(CustomerRepository customerRepository,
                                          AddressRepository addressRepository) {
    this.customerRepository = customerRepository;
    this.addressRepository = addressRepository;
  }

  public Customer execute(long customerId) {
    CustomerEntity customer = customerRepository.findById(customerId)
        .orElseThrow(() -> new IllegalArgumentException("Customer not found: id=" + customerId));
    List<AddressEntity> addresses = addressRepository.findByCustomerId(customerId);
    return new Customer(customer.getId(), customer.getUserId(), customer.getDisplayName(),
        customer.getEmail(), customer.getPhone(), addresses);
  }
}