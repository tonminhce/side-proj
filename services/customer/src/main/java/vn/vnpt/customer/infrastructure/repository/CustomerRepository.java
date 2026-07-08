package vn.vnpt.customer.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.customer.infrastructure.entity.CustomerEntity;

public interface CustomerRepository extends JpaRepository<CustomerEntity, Long> {
}