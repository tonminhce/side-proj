package vn.vnpt.cart.application;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.infrastructure.outbox.CartEventPublisher;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * ExpireCartUseCase — Story 2.2 / FR-18 (cart auto-expire, ADR-04 atomicity).
 *
 * <p>Sweeper-initiated expiry. Each invocation runs in its own transaction
 * ({@code Propagation.REQUIRES_NEW}) so a slow expire on one cart doesn't poison the rest of the
 * sweeper batch (mirrors {@code ReleaseInventoryUseCase.releaseExpired} from Story 1.6).
 *
 * <p>Defensive no-ops when the cart is already terminal or its TTL was extended (future feature);
 * the sweeper may pick up a cart that transitioned to terminal between the query and the lock.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ExpireCartUseCase {

  private final CartRepository cartRepository;
  private final CartLineRepository cartLineRepository;
  private final CartEventPublisher cartEventPublisher;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void expireSingleCart(Long cartUuid) {
    Cart cart =
        cartRepository.findById(cartUuid).orElse(null);
    if (cart == null) {
      log.debug("expireSingleCart: cartUuid={} not found; no-op", cartUuid);
      return;
    }

    if (cart.getStatus() != null && cart.getStatus().isTerminal()) {
      log.debug(
          "expireSingleCart: cartUuid={} already terminal (status={}); no-op",
          cartUuid,
          cart.getStatus());
      return;
    }

    if (cart.getExpiresAt() != null && cart.getExpiresAt().isAfter(Instant.now())) {
      log.debug(
          "expireSingleCart: cartUuid={} not yet expired (expires_at={}); no-op",
          cartUuid,
          cart.getExpiresAt());
      return;
    }

    int expiredLinesCount = (int) cartLineRepository.findByCartUuid(cartUuid).stream()
        .filter(l -> !Boolean.TRUE.equals(l.getIsDeleted()))
        .count();

    // Capture BEFORE the mutation — the event needs the pre-transition status.
    CartStatus previousStatus = cart.getStatus();
    cart.setStatus(CartStatus.ABANDONED);

    // Force-increment the version via the Story 2.1 lock so the ABANDONED transition is a
    // cart-level mutation visible to optimistic-concurrency readers.
    cartRepository.findAndLockByUuid(cartUuid);
    cartRepository.save(cart);

    cartEventPublisher.publishCartExpired(cart, previousStatus, expiredLinesCount);

    log.debug(
        "expireSingleCart: cartUuid={} previousStatus={} lines={}",
        cartUuid,
        previousStatus,
        expiredLinesCount);
  }
}