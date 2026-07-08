package vn.vnpt.order.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Tests for the order-side HMAC event verifier — Story 4.2 / FR-82.
 */
class OrderHmacEventVerifierTest {

  static class StubKeyProvider implements HmacServiceKeyProvider {
    private final String secret;
    StubKeyProvider(String s) { this.secret = s; }
    @Override public String currentSecret() { return secret; }
  }

  @Test
  void verify_returnsTrueOnValidSignature() {
    OrderHmacEventVerifier v = new OrderHmacEventVerifier(new StubKeyProvider("test-secret"));
    String canonical = JcsCanonicalJson.serialize(Map.of("event_id", 42L, "type", "payment.captured"));
    String sig = HmacEventSigner.sign(canonical, "test-secret");
    assertThat(v.verify(canonical, sig)).isTrue();
  }

  @Test
  void verify_returnsFalseOnTamperedCanonicalJson() {
    OrderHmacEventVerifier v = new OrderHmacEventVerifier(new StubKeyProvider("test-secret"));
    String canonical = JcsCanonicalJson.serialize(Map.of("event_id", 42L, "type", "payment.captured"));
    String sig = HmacEventSigner.sign(canonical, "test-secret");
    // Tamper the canonical JSON after signing
    String tampered = canonical.replace("42", "99");
    assertThat(v.verify(tampered, sig)).isFalse();
  }

  @Test
  void verify_returnsFalseOnTamperedSignature() {
    OrderHmacEventVerifier v = new OrderHmacEventVerifier(new StubKeyProvider("test-secret"));
    String canonical = JcsCanonicalJson.serialize(Map.of("event_id", 42L));
    String sig = HmacEventSigner.sign(canonical, "test-secret");
    // Tamper the last char of the signature
    char last = sig.charAt(sig.length() - 1);
    String tamperedSig = sig.substring(0, sig.length() - 1) + (last == 'A' ? 'B' : 'A');
    assertThat(v.verify(canonical, tamperedSig)).isFalse();
  }
}