package vn.vnpt.checkout.application;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.exception.CheckoutNotFoundException;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;

/**
 * Lookup the current state of a checkout — Story 2.3 / FR-21 (checkoutId polling).
 *
 * <p>Returns the persisted {@link Checkout} (status, shipping address, version, cart reference).
 * The polling BFF re-exposes as {@code GET /bff/storefront/checkout/\{id\}} and the storefront polls
 * every 2 seconds for status updates as the saga (Story 2.5) advances.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GetCheckoutUseCase {

  private final CheckoutRepository checkoutRepository;

  /**
   * Returns the checkout or throws {@link CheckoutNotFoundException} (mapped to HTTP 404 by
   * {@code CheckoutControllerExceptionHandler}).
   */
  public Checkout findByCheckoutUuid(Long uuid) {
    return checkoutRepository
        .findByUuid(uuid)
        .orElseThrow(() -> new CheckoutNotFoundException(uuid));
  }

  /** Optional variant for callers that prefer to handle empty directly. */
  public Optional<Checkout> findOptional(Long uuid) {
    return checkoutRepository.findByUuid(uuid);
  }
}