package vn.vnpt.customer.infrastructure.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;

public interface AddressRepository extends JpaRepository<AddressEntity, Long> {
  List<AddressEntity> findByCustomerId(Long customerId);
}