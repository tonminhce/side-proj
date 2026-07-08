package vn.vnpt.customer.application.usecase;

import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.customer.application.port.CreateCustomerCommand;
import vn.vnpt.customer.domain.Customer;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

/**
 * Create a new Customer — Story 5.1 / FR-45. The Customer is separate from the auth User
 * (the {@code userId} is the seam); this story doesn't validate that the auth User exists yet
 * (the auth service lands in Story 5.4).
 */
@Service
@Transactional
public class CreateCustomerUseCase {

  private final CustomerRepository customerRepository;

  public CreateCustomerUseCase(CustomerRepository customerRepository) {
    this.customerRepository = customerRepository;
  }

  public Customer execute(CreateCustomerCommand cmd) {
    CustomerEntity entity = CustomerEntity.builder()
        .userId(cmd.userId())
        .displayName(cmd.displayName())
        .email(cmd.email())
        .phone(cmd.phone())
        .createdAt(LocalDateTime.now())
        .build();
    CustomerEntity saved = customerRepository.save(entity);
    return new Customer(saved.getId(), saved.getUserId(), saved.getDisplayName(),
        saved.getEmail(), saved.getPhone(), java.util.List.of());
  }
}