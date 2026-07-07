package vn.vnpt.cart.application;

import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.exception.CartLineNotFoundException;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * RemoveLineUseCase — Story 2.1 / FR-16 (AC #2, #5).
 *
 * <p>Soft-deletes the line (never row-deletes — the repository has no {@code delete*} method) and
 * force-increments the cart version. A concurrent edit surfaces as {@link
 * CartVersionConflictException} → HTTP 409 with the latest state.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class RemoveLineUseCase {

  private final CartRepository cartRepository;
  private final CartLineRepository cartLineRepository;

  public Cart removeLine(Long cartUuid, Long lineUuid, Long expectedCartVersion) {
    Cart cart =
        cartRepository
            .findAndLockByUuid(cartUuid)
            .orElseThrow(() -> new CartNotFoundException(cartUuid));

    if (expectedCartVersion != null && !expectedCartVersion.equals(cart.getVersion())) {
      throw new CartVersionConflictException(expectedCartVersion, cart.getVersion(), cart);
    }

    CartLine line =
        cartLineRepository
            .findById(lineUuid)
            .filter(l -> l.getCartUuid().equals(cartUuid) && !Boolean.TRUE.equals(l.getIsDeleted()))
            .orElseThrow(() -> new CartLineNotFoundException(cartUuid, lineUuid));

    line.setIsDeleted(true);
    line.setIsActive(false);
    try {
      cartLineRepository.save(line);
      return cartRepository.save(cart);
    } catch (ObjectOptimisticLockingFailureException e) {
      Cart latest = cartRepository.findById(cartUuid).orElse(cart);
      throw new CartVersionConflictException(expectedCartVersion, latest.getVersion(), latest);
    }
  }
}
