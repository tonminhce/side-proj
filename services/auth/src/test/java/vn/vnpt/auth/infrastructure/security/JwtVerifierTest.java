package vn.vnpt.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.vnpt.util.events.JcsCanonicalJson;

class JwtVerifierTest {

  private static final String SHARED_SECRET = "test-shared-hmac-secret-32-bytes-long-xyz";

  private ObjectMapper objectMapper;
  private JwtIssuer issuer;
  private JwtVerifier verifier;
  private Clock now;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    now = Clock.fixed(Instant.parse("2026-07-09T10:00:00Z"), ZoneOffset.UTC);
    issuer = new JwtIssuer(objectMapper, "test", SHARED_SECRET);
    verifier = new JwtVerifier(objectMapper, now, "test", SHARED_SECRET);
  }

  @Test
  void verify_acceptsValidToken() {
    // Use real wall clock for the "happy path" so issuer's Instant.now() and
    // verifier's Instant.now(clock) agree.
    JwtVerifier realNowVerifier = new JwtVerifier(objectMapper, Clock.systemUTC(), "test", SHARED_SECRET);
    String token = issuer.issue(42L, "u@x.vn", "USER");
    Map<String, Object> claims = realNowVerifier.verify(token);
    assertThat(claims.get("iss")).isEqualTo("auth");
    assertThat(claims.get("sub")).isEqualTo(42);
    assertThat(((Number) claims.get("exp")).longValue())
        .isGreaterThan(Instant.now().getEpochSecond());
  }

  @Test
  void verify_rejectsBadSignature() {
    String token = issuer.issue(1L, "u@x.vn", "USER");
    int dot = token.lastIndexOf('.');
    String sig = token.substring(dot + 1);
    char flipped = sig.charAt(sig.length() - 1) == 'A' ? 'B' : 'A';
    String tampered = token.substring(0, dot + 1) + sig.substring(0, sig.length() - 1) + flipped;
    assertThatThrownBy(() -> verifier.verify(tampered)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("signature");
  }

  @Test
  void verify_rejectsExpired() {
    // Clock 2h ahead — issuer's exp = issueTime+3600, verifier's "now" past that.
    Clock future = Clock.fixed(Instant.parse("2026-07-09T13:00:00Z"), ZoneOffset.UTC);
    JwtVerifier futureVerifier = new JwtVerifier(objectMapper, future, "test", SHARED_SECRET);
    String token = issuer.issue(1L, "u@x.vn", "USER");
    assertThatThrownBy(() -> futureVerifier.verify(token)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void verify_rejectsWrongIssuer() {
    Map<String, Object> forged = new LinkedHashMap<>();
    forged.put("iss", "evil");
    forged.put("sub", 1L);
    forged.put("exp", 9_999_999_999L);
    String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
        JcsCanonicalJson.serialize(forged).getBytes(StandardCharsets.UTF_8));
    String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String forgedToken = headerB64 + "." + payloadB64 + ".AAAA";
    assertThatThrownBy(() -> verifier.verify(forgedToken))
        .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class);
  }

  @Test
  void verify_rejectsMalformed() {
    assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("malformed");
    assertThatThrownBy(() -> verifier.verify(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> verifier.verify(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}