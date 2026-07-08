package vn.vnpt.payment.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev HMAC key provider — Story 3.5 / FR-82. Active only in the {@code dev} profile.
 *
 * <p>Reads {@code HMAC_SERVICE_SECRET} env var; falls back to a 32-byte random hex generated at
 * startup (logged ONCE so the dev can curl-test signing). The fallback is dev-only — prod path
 * requires the env var to be set explicitly per the Vault integration story.
 */
@Component
@Profile("dev")
public class DevHmacKeyProvider implements HmacServiceKeyProvider {

  private static final Logger log = LoggerFactory.getLogger(DevHmacKeyProvider.class);
  private volatile String secret;

  @PostConstruct
  public void init() {
    String envSecret = System.getenv("HMAC_SERVICE_SECRET");
    if (envSecret != null && !envSecret.isBlank()) {
      this.secret = envSecret;
      log.info("DevHmacKeyProvider using HMAC_SERVICE_SECRET env var ({} chars)", envSecret.length());
    } else {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      this.secret = java.util.HexFormat.of().formatHex(random);
      log.warn("DevHmacKeyProvider generated random HMAC secret (dev only, never use in prod): {}",
          this.secret);
    }
  }

  @Override
  public String currentSecret() {
    return secret;
  }
}