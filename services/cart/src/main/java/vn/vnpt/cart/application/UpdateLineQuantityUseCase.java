package vn.vnpt.cart.application;

import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.exception.CartLineNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * UpdateLineQuantityUseCase — Story 2.1 / FR-16 (AC #2, #5).
 *
 * <p>PATCH-style: sets the absolute quantity on a line and increments the line version. Throws
 * {@link CartLineNotFoundException} if the line is not in the cart, or {@link
 * CartVersionConflictException} on a concurrent edit.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class UpdateLineQuantityUseCase {

  private final CartRepository cartRepository;
  private final CartLineRepository cartLineRepository;

  public CartLine updateQuantity(
      Long cartUuid, Long lineUuid, int quantity, Long expectedLineVersion) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    CartLine line =
        cartLineRepository
            .findById(lineUuid)
            .filter(l -> l.getCartUuid().equals(cartUuid) && !Boolean.TRUE.equals(l.getIsDeleted()))
            .orElseThrow(() -> new CartLineNotFoundException(cartUuid, lineUuid));

    if (expectedLineVersion != null && !expectedLineVersion.equals(line.getVersion())) {
      throw new CartVersionConflictException(expectedLineVersion, line.getVersion(), null);
    }

    line.setQuantity(quantity);
    try {
      return cartLineRepository.save(line);
    } catch (ObjectOptimisticLockingFailureException e) {
      CartLine latest = cartLineRepository.findById(lineUuid).orElse(line);
      throw new CartVersionConflictException(expectedLineVersion, latest.getVersion(), null);
    }
  }
}
