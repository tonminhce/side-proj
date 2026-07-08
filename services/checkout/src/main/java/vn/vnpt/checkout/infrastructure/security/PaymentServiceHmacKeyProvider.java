package vn.vnpt.checkout.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * HMAC key provider for the payment service (cross-service) — Story 3.5 follow-up.
 * Reads the payment service's secret from the {@code HMAC_SERVICE_SECRET} env var
 * (the same key payment uses to sign envelopes via util's HmacEventSigner).
 * 5-min cache per ADR-20; fail-loud at startup (prod path), fail-safe with cache fallback
 * (subsequent Vault outages).
 */
@Component
@Profile("!dev")
public class PaymentServiceHmacKeyProvider implements HmacServiceKeyProvider {

  private static final Logger log = LoggerFactory.getLogger(PaymentServiceHmacKeyProvider.class);
  private static final long CACHE_TTL_MINUTES = 5;

  private final AtomicReference<String> cachedSecret = new AtomicReference<>();
  private volatile long lastFetchedMs = 0;

  @PostConstruct
  public void init() {
    String initial = readFromVault();
    if (initial == null || initial.isBlank()) {
      throw new IllegalStateException(
          "Payment service HMAC key (secret/events/hmac/payment) is empty — checkout refuses to start"
              + " without verifying the producer's signatures (ADR-20 fail-loud). Set HMAC_SERVICE_SECRET.");
    }
    cachedSecret.set(initial);
    lastFetchedMs = System.currentTimeMillis();
    log.info("PaymentServiceHmacKeyProvider initialized (cached for {} min)", CACHE_TTL_MINUTES);
  }

  @Override
  public String currentSecret() {
    if (System.currentTimeMillis() - lastFetchedMs > Duration.ofMinutes(CACHE_TTL_MINUTES).toMillis()) {
      String refreshed = readFromVault();
      if (refreshed != null && !refreshed.isBlank()) {
        cachedSecret.set(refreshed);
        lastFetchedMs = System.currentTimeMillis();
      } else {
        log.warn("Vault unreachable during payment HMAC secret refresh; using cached value");
      }
    }
    return cachedSecret.get();
  }

  private String readFromVault() {
    return System.getenv().getOrDefault("HMAC_SERVICE_SECRET", "MISSING_VAULT_KEY");
  }
}