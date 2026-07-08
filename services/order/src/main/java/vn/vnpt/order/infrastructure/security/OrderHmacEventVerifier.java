package vn.vnpt.order.infrastructure.security;

import org.springframework.stereotype.Component;
import vn.vnpt.util.events.HmacEventSigner;

/**
 * HMAC event verifier for the order-side saga listener — Story 4.2 / FR-82 / ADR-20.
 * Constant-time compare via {@link HmacEventSigner#verify}.
 *
 * <p>// TODO ops hardening: cross-service Vault path isolation. v1 uses a shared secret loaded
 * from the producer's env var via the same key-loading pattern as the producer (ADR-20 fail-safe
 * with 5-min cache). Future story wires per-service Vault paths.
 */
@Component
public class OrderHmacEventVerifier {

  private final HmacServiceKeyProvider paymentServiceKeyProvider;

  public OrderHmacEventVerifier(HmacServiceKeyProvider paymentServiceKeyProvider) {
    this.paymentServiceKeyProvider = paymentServiceKeyProvider;
  }

  public boolean verify(String canonicalJson, String signatureB64Url) {
    if (canonicalJson == null || signatureB64Url == null) {
      return false;
    }
    return HmacEventSigner.verify(canonicalJson, signatureB64Url, paymentServiceKeyProvider.currentSecret());
  }
}