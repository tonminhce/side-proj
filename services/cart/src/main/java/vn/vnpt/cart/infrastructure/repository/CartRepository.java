package vn.vnpt.cart.infrastructure.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;

/**
 * Spring Data JPA repository for {@link Cart} — Story 2.1, extended in Story 2.2 with the sweeper
 * query.
 *
 * <p>NO {@code void delete*(...)} methods: cart rows transition status (MERGED / ABANDONED /
 * CHECKED_OUT), never row-deleted. Enforced by {@code CartPackageBoundaryTest}.
 */
public interface CartRepository extends JpaRepository<Cart, Long> {

  /** User-bound lookup for {@code GetOrCreateCartUseCase} (AC #3). */
  Optional<Cart> findByTenantIdAndUserIdAndStatus(String tenantId, String userId, CartStatus status);

  /** Anonymous lookup for {@code GetOrCreateCartUseCase} (AC #3). */
  Optional<Cart> findByTenantIdAndGuestCartIdAndStatus(
      String tenantId, String guestCartId, CartStatus status);

  /**
   * Load a cart and force a {@code @Version} bump at flush (FR-16). Used by the mutating use cases
   * ({@code AddLineUseCase} / {@code RemoveLineUseCase}) so a line change increments the parent
   * cart's version even though no cart column itself changes.
   */
  @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
  @Query("select c from Cart c where c.uuid = :uuid")
  Optional<Cart> findAndLockByUuid(@Param("uuid") Long uuid);

  /**
   * Pessimistic lock on the anonymous cart row used by {@code MergeCartUseCase} to serialize
   * concurrent merges of the same guest cart and prevent the ownership-conflict TOCTOU race
   * (two different users claiming the same {@code guest_cart_id} at the same instant).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Cart c where c.tenantId = :tenantId and c.guestCartId = :guestCartId and c.status = vn.vnpt.cart.domain.CartStatus.ANONYMOUS")
  Optional<Cart> lockAnonymousCart(
      @Param("tenantId") String tenantId, @Param("guestCartId") String guestCartId);

  /**
   * Story 2.2 / FR-18 sweeper query — returns carts in the given statuses whose TTL has passed,
   * ordered by {@code expires_at} ASC so the oldest expires first. Mirrors Story 1.6's
   * {@code findByStatusAndExpiresAtBefore} precedent. The partial index
   * {@code idx_carts_status_expires_at} (V002) keeps the scan O(rows-to-expire).
   */
  List<Cart> findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(
      Collection<CartStatus> statuses, Instant cutoff);
}
