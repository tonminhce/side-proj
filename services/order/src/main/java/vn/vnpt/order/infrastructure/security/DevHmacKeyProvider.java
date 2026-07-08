package vn.vnpt.order.infrastructure.security;

import jakarta.annotation.PostConstruct;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev HMAC key provider for the order service — Story 4.1. Reads {@code HMAC_SERVICE_SECRET_ORDER}
 * env var; falls back to a 32-byte random hex generated at startup.
 */
@Component
@Profile("dev")
public class DevHmacKeyProvider implements HmacServiceKeyProvider {

  private static final Logger log = LoggerFactory.getLogger(DevHmacKeyProvider.class);
  private volatile String secret;

  @PostConstruct
  public void init() {
    String envSecret = System.getenv("HMAC_SERVICE_SECRET_ORDER");
    if (envSecret != null && !envSecret.isBlank()) {
      this.secret = envSecret;
      log.info("DevHmacKeyProvider(order) using HMAC_SERVICE_SECRET_ORDER env var ({} chars)", envSecret.length());
    } else {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      this.secret = java.util.HexFormat.of().formatHex(random);
      log.warn("DevHmacKeyProvider(order) generated random HMAC secret (dev only, never use in prod): {}",
          this.secret);
    }
  }

  @Override
  public String currentSecret() {
    return secret;
  }
}