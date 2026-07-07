package vn.vnpt.checkout.infrastructure.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.vnpt.checkout.domain.Checkout;

/**
 * Spring Data JPA repository for {@link Checkout} — Story 2.3 / FR-19, FR-21.
 *
 * <p>NO {@code void delete*(...)} methods: checkout rows transition status
 * ({@code PAYMENT_PENDING → PAID/FAILED/CANCELLED/EXPIRED}), never row-deleted. Enforced by
 * {@code CheckoutPackageBoundaryTest}.
 */
public interface CheckoutRepository extends JpaRepository<Checkout, Long> {

  /** Lookup for {@code GetCheckoutUseCase} (FR-21 polling). */
  Optional<Checkout> findByUuid(Long uuid);

  /**
   * Load a checkout and force a {@code @Version} bump at flush. Used by mutating use cases (Story 2.5
   * saga) so a status change increments the row's version even when no other column changes.
   */
  @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
  @Query("select c from Checkout c where c.uuid = :uuid")
  Optional<Checkout> findAndLockByUuid(@Param("uuid") Long uuid);
}