package vn.vnpt.cart.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.CartNotFoundException;
import vn.vnpt.cart.infrastructure.repository.CartRepository;
import vn.vnpt.util.component.softdelete.validator.UkValidator;

/**
 * GetOrCreateCartUseCase — Story 2.1 / FR-14 (AC #3).
 *
 * <p>Single entry point for resolving a cart. User-bound carts are keyed on {@code (tenantId,
 * userId)} where {@code status = ACTIVE}; anonymous carts on {@code (tenantId, guestCartId)} where
 * {@code status = ANONYMOUS}. The {@code UkValidator} fires before {@code save} to enforce the
 * {@code @SoftUk} invariant on {@code Cart} (AC #8).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class GetOrCreateCartUseCase {

  private static final String DEFAULT_TENANT = "default";

  private final CartRepository cartRepository;
  private final UkValidator ukValidator;

  /**
   * Return the existing cart for the identifier, or create a new one.
   *
   * @param guestCartId cookie UUID (anonymous path); may be null
   * @param userId auth user id (user-bound path); may be null
   * @throws IllegalArgumentException if both identifiers are null (mapped to 400)
   */
  public Cart getOrCreate(String guestCartId, String userId) {
    if (userId != null && !userId.isBlank()) {
      return cartRepository
          .findByTenantIdAndUserIdAndStatus(DEFAULT_TENANT, userId, CartStatus.ACTIVE)
          .orElseGet(
              () ->
                  create(
                      Cart.builder()
                          .tenantId(DEFAULT_TENANT)
                          .userId(userId)
                          .status(CartStatus.ACTIVE)
                          .build()));
    }
    if (guestCartId != null && !guestCartId.isBlank()) {
      return cartRepository
          .findByTenantIdAndGuestCartIdAndStatus(DEFAULT_TENANT, guestCartId, CartStatus.ANONYMOUS)
          .orElseGet(
              () ->
                  create(
                      Cart.builder()
                          .tenantId(DEFAULT_TENANT)
                          .guestCartId(guestCartId)
                          .status(CartStatus.ANONYMOUS)
                          .build()));
    }
    throw new IllegalArgumentException("At least one of guestCartId or userId is required");
  }

  /** Explicit lookup; throws {@link CartNotFoundException} (the getOrCreate path never does). */
  @Transactional(readOnly = true)
  public Cart findByUuid(Long cartUuid) {
    return cartRepository.findById(cartUuid).orElseThrow(() -> new CartNotFoundException(cartUuid));
  }

  private Cart create(Cart cart) {
    // ponytail: v1 single-tenant application-layer @SoftUk guard (mirrors CreateWarehouseUseCase).
    ukValidator.validate(cart);
    return cartRepository.save(cart);
  }
}
