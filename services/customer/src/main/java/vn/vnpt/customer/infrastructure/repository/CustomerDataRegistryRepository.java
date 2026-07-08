package vn.vnpt.customer.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.customer.infrastructure.entity.CustomerDataRegistryEntity;

public interface CustomerDataRegistryRepository extends JpaRepository<CustomerDataRegistryEntity, Long> {
}