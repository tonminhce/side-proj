package vn.vnpt.customer.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.customer.application.port.ForgetResponse;
import vn.vnpt.customer.infrastructure.entity.CustomerDataRegistryEntity;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;
import vn.vnpt.customer.infrastructure.repository.CustomerDataRegistryRepository;
import vn.vnpt.customer.infrastructure.repository.CustomerRepository;

/** Right-to-be-forgotten — Story 5.2 / FR-49. Hard-deletes the customer (cascade-deletes
 *  addresses) + inserts a forget_audit row in customer_data_registry. Atomic in one
 *  transaction; if the audit insert fails, the customer is NOT deleted. */
@Service
@Transactional
public class ForgetCustomerUseCase {

  private final CustomerRepository customerRepository;
  private final CustomerDataRegistryRepository registryRepository;
  private final Clock clock;

  public ForgetCustomerUseCase(
      CustomerRepository customerRepository,
      CustomerDataRegistryRepository registryRepository,
      Clock clock) {
    this.customerRepository = customerRepository;
    this.registryRepository = registryRepository;
    this.clock = clock;
  }

  public ForgetResponse execute(long customerId) {
    CustomerEntity customer = customerRepository.findById(customerId)
        .orElseThrow(() -> new IllegalArgumentException("Customer not found: id=" + customerId));
    Instant now = Instant.now(clock);
    customerRepository.delete(customer);
    // Audit row: the customer_data_registry table stores schema + a JSON payload; for the
    // forget_audit row the columns JSONB carries the actual values (customer_id, forgotten_at).
    // The schema is also declared for downstream consumers that introspect the registry.
    registryRepository.save(CustomerDataRegistryEntity.builder()
        .serviceName("customer_service")
        .tableName("forget_audit")
        .columns(String.format("{\"customer_id\":%d,\"forgotten_at\":\"%s\"}",
            customerId, now.toString()))
        .format("json")
        .createdAt(LocalDateTime.now(clock))
        .build());
    return new ForgetResponse(customerId, now);
  }
}