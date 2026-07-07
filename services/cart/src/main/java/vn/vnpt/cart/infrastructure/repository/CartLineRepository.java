package vn.vnpt.cart.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.vnpt.cart.domain.CartLine;

/**
 * Spring Data JPA repository for {@link CartLine} — Story 2.1.
 *
 * <p>NO {@code void delete*(...)} methods: removal goes through {@code RemoveLineUseCase} which
 * soft-deletes ({@code save(line.setIsDeleted(true))}), never {@code deleteById(uuid)}. Enforced by
 * {@code CartPackageBoundaryTest.cart_lines_isTerminalOrAppendOnly}.
 *
 * <p>{@link #findActiveByCartUuidAndVariantId} excludes soft-deleted rows so that a remove-then-
 * re-add of the same variant creates a fresh line (not a hidden sum on a tombstone).
 */
public interface CartLineRepository extends JpaRepository<CartLine, Long> {

  List<CartLine> findByCartUuid(Long cartUuid);

  Optional<CartLine> findByCartUuidAndVariantId(Long cartUuid, Long variantId);

  /** Active-only lookup: {@code is_deleted = false}. Used by mutating use cases (FR-15 sum-quantity). */
  @Query("select l from CartLine l where l.cartUuid = :cartUuid and l.variantId = :variantId and l.isDeleted = false")
  Optional<CartLine> findActiveByCartUuidAndVariantId(
      @Param("cartUuid") Long cartUuid, @Param("variantId") Long variantId);
}
