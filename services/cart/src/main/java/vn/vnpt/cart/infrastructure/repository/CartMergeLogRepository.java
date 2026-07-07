package vn.vnpt.cart.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.cart.domain.CartMergeLog;

/**
 * Spring Data JPA repository for {@link CartMergeLog} — Story 2.1.
 *
 * <p>NO {@code void delete*(...)} methods: the merge log is an append-only audit trail. Enforced by
 * {@code CartPackageBoundaryTest.cart_mergeLog_isAppendOnly}.
 */
public interface CartMergeLogRepository extends JpaRepository<CartMergeLog, Long> {

  Optional<CartMergeLog> findByIdempotencyKey(String idempotencyKey);

  boolean existsByIdempotencyKey(String idempotencyKey);

  /**
   * All merge-log rows for a guest cart. Used to detect the ownership conflict (AC #6): if a
   * different {@code userId} already merged this {@code guestCartId}, the caller gets a 409.
   */
  List<CartMergeLog> findByGuestCartId(String guestCartId);
}
