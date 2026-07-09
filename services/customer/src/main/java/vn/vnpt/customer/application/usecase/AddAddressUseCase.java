package vn.vnpt.customer.application.usecase;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.customer.application.port.AddAddressCommand;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.AddressRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

/**
 * Add an Address to a Customer — Story 5.1 / FR-47. The Address is owned by the Customer
 * aggregate (FK with ON DELETE CASCADE).
 */
@Service
@Transactional
public class AddAddressUseCase {

  private final CustomerRepository customerRepository;
  private final AddressRepository addressRepository;
  private final Clock clock;

  public AddAddressUseCase(CustomerRepository customerRepository,
                            AddressRepository addressRepository,
                            Clock clock) {
    this.customerRepository = customerRepository;
    this.addressRepository = addressRepository;
    this.clock = clock;
  }

  public long execute(AddAddressCommand cmd) {
    CustomerEntity customer = customerRepository.findById(cmd.customerId())
        .orElseThrow(() -> new IllegalArgumentException("Customer not found: id=" + cmd.customerId()));
    // Story 5.1 / FR-47 — at most one default address per customer. Unset prior defaults
    // before inserting a new default row (app-layer guard; matches the project convention
    // of avoiding nullable columns when a CHECK would suffice).
    if (cmd.isDefault()) {
      List<AddressEntity> priorDefaults = addressRepository.findByCustomerId(cmd.customerId())
          .stream().filter(AddressEntity::isDefault).toList();
      priorDefaults.forEach(a -> a.setDefault(false));
      addressRepository.saveAll(priorDefaults);
    }
    AddressEntity address = AddressEntity.builder()
        .customer(customer)
        .line1(cmd.line1())
        .provinceCode(cmd.provinceCode())
        .districtCode(cmd.districtCode())
        .communeCode(cmd.communeCode())
        .isDefault(cmd.isDefault())
        .createdAt(LocalDateTime.now(clock))
        .build();
    return addressRepository.save(address).getId();
  }
}