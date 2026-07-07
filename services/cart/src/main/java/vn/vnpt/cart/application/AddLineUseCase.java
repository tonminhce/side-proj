package vn.vnpt.cart.application;

import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.domain.exception.CartVersionConflictException;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * AddLineUseCase — Story 2.1 / FR-15, FR-16 (AC #2, #5).
 *
 * <p>Adds a line, or sums the quantity if {@code (cart_uuid, variant_id)} already exists. Increments
 * the cart version (force-increment on the parent) so an add is a cart mutation (FR-16). An explicit
 * {@code expectedVersion} mismatch, or a Hibernate {@code ObjectOptimisticLockingFailureException} on
 * flush, surfaces as {@link CartVersionConflictException} → HTTP 409 with the latest state.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class AddLineUseCase {

  private final CartRepository cartRepository;
  private final CartLineRepository cartLineRepository;

  public Cart addLine(Long cartUuid, Long variantId, int quantity, Long expectedVersion) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    Cart cart =
        cartRepository
            .findAndLockByUuid(cartUuid)
            .orElseThrow(() -> new CartNotFoundException(cartUuid));

    if (expectedVersion != null && !expectedVersion.equals(cart.getVersion())) {
      throw new CartVersionConflictException(expectedVersion, cart.getVersion(), cart);
    }

    try {
      CartLine line =
          cartLineRepository
              .findByCartUuidAndVariantId(cartUuid, variantId)
              .map(
                  existing -> {
                    existing.setQuantity(existing.getQuantity() + quantity);
                    return existing;
                  })
              .orElseGet(
                  () ->
                      CartLine.builder()
                          .cartUuid(cartUuid)
                          .tenantId(cart.getTenantId())
                          .variantId(variantId)
                          .quantity(quantity)
                          .build());
      cartLineRepository.save(line);
      return cartRepository.save(cart);
    } catch (ObjectOptimisticLockingFailureException e) {
      Cart latest = cartRepository.findById(cartUuid).orElse(cart);
      throw new CartVersionConflictException(expectedVersion, latest.getVersion(), latest);
    }
  }
}
