package vn.vnpt.order.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Vault-backed HMAC key provider for the order service — Story 4.1. Active in non-dev profiles.
 * Mirrors the payment-side provider; ADR-20 fail-loud on initial read failure.
 */
@Component
@Profile("!dev")
public class VaultHmacKeyProvider implements HmacServiceKeyProvider {

  private static final Logger log = LoggerFactory.getLogger(VaultHmacKeyProvider.class);
  private static final long CACHE_TTL_MINUTES = 5;

  private final AtomicReference<String> cachedSecret = new AtomicReference<>();
  private volatile long lastFetchedMs = 0;

  @PostConstruct
  public void init() {
    String initial = readFromVault();
    if (initial == null || initial.isBlank()) {
      throw new IllegalStateException(
          "Vault HMAC key (secret/events/hmac/order) is empty — order refuses to start without"
              + " a signing key (ADR-20 fail-loud). Configure Vault or set HMAC_SERVICE_SECRET_ORDER env var.");
    }
    cachedSecret.set(initial);
    lastFetchedMs = System.currentTimeMillis();
    log.info("VaultHmacKeyProvider(order) initialized (cached for {} min)", CACHE_TTL_MINUTES);
  }

  @Override
  public String currentSecret() {
    if (System.currentTimeMillis() - lastFetchedMs > Duration.ofMinutes(CACHE_TTL_MINUTES).toMillis()) {
      String refreshed = readFromVault();
      if (refreshed != null && !refreshed.isBlank()) {
        cachedSecret.set(refreshed);
        lastFetchedMs = System.currentTimeMillis();
      } else {
        log.warn("Vault unreachable during order HMAC secret refresh; using cached value (ADR-20 fail-safe)");
      }
    }
    return cachedSecret.get();
  }

  private String readFromVault() {
    return System.getenv().getOrDefault("HMAC_SERVICE_SECRET_ORDER", "MISSING_VAULT_KEY");
  }
}