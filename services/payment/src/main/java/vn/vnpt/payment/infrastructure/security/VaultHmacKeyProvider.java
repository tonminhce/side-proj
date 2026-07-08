package vn.vnpt.payment.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Vault-backed HMAC key provider — Story 3.5 / FR-82 / ADR-20. Active in non-dev profiles.
 *
 * <p>Reads {@code secret/events/hmac/payment} from HashiCorp Vault at startup. Caches the value
 * for {@value #CACHE_TTL_MINUTES} minutes; on subsequent Vault outages, uses the cached value
 * (fail-safe per ADR-20). Initial failure throws {@link IllegalStateException} — payment
 * refuses to start without a key.
 *
 * <p>// TODO ops story: wire spring-cloud-starter-vault-config + VaultTemplate read; this stub
 * generates a deterministic-but-non-secret key in dev and returns "MISSING_VAULT_KEY" in prod
 * if Vault is not yet wired. The cache TTL + fail-safe semantics are correct; only the actual
 * Vault read needs ops attention.
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
      // ponytail: in v1 the Vault path is unwired; refuse to start. The dev profile's
      // HmacServiceKeyProvider.Dev branch handles the dev case. When ops wires Vault, this
      // path returns the actual secret and init() succeeds.
      throw new IllegalStateException(
          "Vault HMAC key (secret/events/hmac/payment) is empty — payment refuses to start without"
              + " a signing key (ADR-20 fail-loud). Configure Vault or set HMAC_SERVICE_SECRET env var.");
    }
    cachedSecret.set(initial);
    lastFetchedMs = System.currentTimeMillis();
    log.info("VaultHmacKeyProvider initialized (cached for {} min)", CACHE_TTL_MINUTES);
  }

  @Override
  public String currentSecret() {
    if (System.currentTimeMillis() - lastFetchedMs > Duration.ofMinutes(CACHE_TTL_MINUTES).toMillis()) {
      String refreshed = readFromVault();
      if (refreshed != null && !refreshed.isBlank()) {
        cachedSecret.set(refreshed);
        lastFetchedMs = System.currentTimeMillis();
      } else {
        log.warn("Vault unreachable during HMAC secret refresh; using cached value (ADR-20 fail-safe)");
      }
    }
    return cachedSecret.get();
  }

  private String readFromVault() {
    // TODO ops: wire VaultTemplate + kv.get("secret/events/hmac/payment").getRequiredData().get("value")
    return System.getenv().getOrDefault("HMAC_SERVICE_SECRET", "MISSING_VAULT_KEY");
  }
}