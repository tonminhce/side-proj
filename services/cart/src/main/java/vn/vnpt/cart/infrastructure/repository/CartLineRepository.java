package vn.vnpt.cart.infrastructure.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.vnpt.cart.domain.CartLine;

/**
 * Spring Data JPA repository for {@link CartLine} — Story 2.1.
 *
 * <p>NO {@code void delete*(...)} methods: removal goes through {@code RemoveLineUseCase} which
 * soft-deletes ({@code save(line.setIsDeleted(true))}), never {@code deleteById(uuid)}. Enforced by
 * {@code CartPackageBoundaryTest.cart_lines_isTerminalOrAppendOnly}.
 */
public interface CartLineRepository extends JpaRepository<CartLine, Long> {

  List<CartLine> findByCartUuid(Long cartUuid);

  Optional<CartLine> findByCartUuidAndVariantId(Long cartUuid, Long variantId);
}
