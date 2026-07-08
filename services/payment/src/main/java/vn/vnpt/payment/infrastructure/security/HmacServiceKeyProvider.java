package vn.vnpt.payment.infrastructure.security;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.vnpt.payment.infrastructure.security.VaultHmacKeyProvider;

/**
 * HMAC service key provider — Story 3.5 / FR-82 / ADR-20 / AT-03.
 *
 * <p>{@link #currentSecret()} returns the per-service HMAC secret used to sign outbox events.
 * Two implementations:
 * <ul>
 *   <li>{@link VaultHmacKeyProvider} (prod / non-dev): reads {@code secret/events/hmac/payment}
 *       from HashiCorp Vault, caches for 5 min, fail-loud on initial read failure (ADR-20).</li>
 *   <li>Dev profile: reads {@code HMAC_SERVICE_SECRET} env var; falls back to a 32-byte random hex
 *       generated at startup (logged ONCE so the dev can curl-test signing).</li>
 * </ul>
 */
public interface HmacServiceKeyProvider {

  /** Returns the current HMAC secret. Throws on initial failure (prod) or returns a dev key (dev). */
  String currentSecret();
}