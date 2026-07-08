package vn.vnpt.auth.infrastructure.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.auth.infrastructure.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
  Optional<UserEntity> findByEmail(String email);
}