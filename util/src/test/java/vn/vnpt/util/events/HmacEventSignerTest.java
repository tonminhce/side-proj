package vn.vnpt.util.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * HMAC-SHA-256 sign + verify tests (Story 1.3 / AC #7 / ADR-20).
 *
 * <p>Four invariants:
 *
 * <ul>
 *   <li>Deterministic: same input + same secret → same signature (replayable).
 *   <li>Round-trip: sign then verify → true.
 *   <li>Secret mismatch: sign with A, verify with B → false.
 *   <li>Tamper detection: sign {@code "a"}, verify {@code "b"} → false.
 * </ul>
 */
class HmacEventSignerTest {

  @Test
  void sign_producesDeterministicBase64Url() {
    String a = HmacEventSigner.sign("hello", "secret-1");
    String b = HmacEventSigner.sign("hello", "secret-1");
    assertThat(a).isEqualTo(b);
    // base64url, no padding, only URL-safe alphabet
    assertThat(a).matches("^[A-Za-z0-9_-]+$");
  }

  @Test
  void verify_acceptsValidSignature() {
    String payload = "{\"k\":\"v\"}";
    String sig = HmacEventSigner.sign(payload, "topsecret");
    assertThat(HmacEventSigner.verify(payload, sig, "topsecret")).isTrue();
  }

  @Test
  void verify_rejectsMismatchedSignature() {
    String payload = "{\"k\":\"v\"}";
    String sig = HmacEventSigner.sign(payload, "secret-A");
    assertThat(HmacEventSigner.verify(payload, sig, "secret-B")).isFalse();
  }

  @Test
  void verify_rejectsTamperedPayload() {
    String sig = HmacEventSigner.sign("a", "topsecret");
    assertThat(HmacEventSigner.verify("b", sig, "topsecret")).isFalse();
  }
}
