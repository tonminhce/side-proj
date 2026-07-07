package vn.vnpt.cart.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * Cart auto-expire sweeper — Story 2.2 / FR-18 (30-day TTL reclaim).
 *
 * <p>Mirrors {@code vn.vnpt.inventory.application.ReservationSweeperJob}: {@code @Scheduled fixedDelay}
 * + batch-bounded + per-row {@code REQUIRES_NEW} via {@code ExpireCartUseCase.expireSingleCart}. A
 * slow expire on one cart does NOT poison the batch.
 */
@Component
@Slf4j
public class CartAutoExpireSweeperJob {

  private static final List<CartStatus> EXPIRABLE_STATUSES =
      List.of(CartStatus.ANONYMOUS, CartStatus.ACTIVE);

  private final CartRepository cartRepository;
  private final ExpireCartUseCase expireCartUseCase;
  private final Counter expiredCounter;

  @Value("${cart.auto-expire.sweeper-batch-size:100}")
  private int batchSize;

  public CartAutoExpireSweeperJob(
      CartRepository cartRepository,
      ExpireCartUseCase expireCartUseCase,
      MeterRegistry meterRegistry) {
    this.cartRepository = cartRepository;
    this.expireCartUseCase = expireCartUseCase;
    this.expiredCounter =
        Counter.builder("cart.auto_expire.expired")
            .description("Number of carts expired by the sweeper")
            .register(meterRegistry);
  }

  @Scheduled(fixedDelayString = "${cart.auto-expire.sweeper-interval-ms:300000}")
  public void sweep() {
    Instant cutoff = Instant.now();
    List<Cart> expired =
        cartRepository
            .findByStatusInAndExpiresAtBeforeOrderByExpiresAtAsc(EXPIRABLE_STATUSES, cutoff)
            .stream()
            .limit(batchSize)
            .toList();

    if (expired.isEmpty()) {
      return;
    }

    long start = System.currentTimeMillis();
    int released = 0;
    for (Cart cart : expired) {
      try {
        // Per-row REQUIRES_NEW in ExpireCartUseCase — a slow expire doesn't poison the batch.
        expireCartUseCase.expireSingleCart(cart.getUuid());
        expiredCounter.increment();
        released++;
      } catch (Exception e) {
        // ponytail: log + continue. A single bad expire shouldn't kill the sweeper.
        log.error("Sweeper failed to expire cartUuid={}", cart.getUuid(), e);
      }
    }

    log.info(
        "Sweeper expired: count={} of batch={} duration_ms={}",
        released,
        expired.size(),
        System.currentTimeMillis() - start);
  }

  /** Exposed for tests — returns the configured batch size. */
  public int getBatchSize() {
    return batchSize;
  }
}