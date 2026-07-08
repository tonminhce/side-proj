package vn.vnpt.checkout.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/**
 * Tests for the consumer-side HMAC verifier — Story 3.5 follow-up / FR-82.
 */
class PaymentEventSignatureVerifierTest {

  static class StubKeyProvider implements HmacServiceKeyProvider {
    private final String secret;
    StubKeyProvider(String s) { this.secret = s; }
    @Override public String currentSecret() { return secret; }
  }

  private PaymentEventSignatureVerifier verifier;

  @BeforeEach
  void setUp() {
    verifier = new PaymentEventSignatureVerifier(new StubKeyProvider("test-secret"),
        new SimpleMeterRegistry());
  }

  @Test
  void verify_returnsTrueOnValidSignature() {
    String canonical = JcsCanonicalJson.serialize(Map.of("event_id", 42L, "type", "payment.captured"));
    String sig = HmacEventSigner.sign(canonical, "test-secret");
    assertThat(verifier.verify(canonical, sig)).isTrue();
  }

  @Test
  void verify_returnsFalseOnTamperedCanonicalJson() {
    String canonical = JcsCanonicalJson.serialize(Map.of("event_id", 42L));
    String sig = HmacEventSigner.sign(canonical, "test-secret");
    String tampered = canonical.replace("42", "99");
    assertThat(verifier.verify(tampered, sig)).isFalse();
  }

  @Test
  void verify_returnsFalseOnWrongKey() {
    String canonical = JcsCanonicalJson.serialize(Map.of("event_id", 42L));
    String sig = HmacEventSigner.sign(canonical, "different-secret");
    assertThat(verifier.verify(canonical, sig)).isFalse();
  }

  @Test
  void verify_returnsFalseOnNullArguments() {
    assertThat(verifier.verify(null, "sig")).isFalse();
    assertThat(verifier.verify("canonical", null)).isFalse();
  }
}