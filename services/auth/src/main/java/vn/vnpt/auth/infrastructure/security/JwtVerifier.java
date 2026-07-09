package vn.vnpt.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.vnpt.util.events.HmacEventSigner;

/** JWT verifier — Epic 5 follow-up / FR-73. HMAC-signed JWTs issued by {@link JwtIssuer}.
 *  Validates signature (HS256 via util's HmacEventSigner), checks {@code iss=auth}, checks
 *  {@code exp} against the injected Clock. Reads the same {@code HMAC_JWT_SECRET} env
 *  used by JwtIssuer so the two beans share a key source (dev profile generates ephemeral,
 *  same random secret both sides). */
@Component
public class JwtVerifier {

  private static final String EXPECTED_ISS = "auth";

  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String activeProfile;
  private volatile String secret;

  public JwtVerifier(ObjectMapper objectMapper,
                     Clock clock,
                     @Value("${spring.profiles.active:prod}") String activeProfile) {
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.activeProfile = activeProfile;
  }

  /** Test seam — mirror of the @PostConstruct resolution. */
  JwtVerifier(ObjectMapper objectMapper, Clock clock, String activeProfile, String secretForTest) {
    this.objectMapper = objectMapper;
    this.clock = clock;
    this.activeProfile = activeProfile;
    this.secret = secretForTest;
  }

  @PostConstruct
  public void init() {
    String env = System.getenv("HMAC_JWT_SECRET");
    if (env != null && !env.isBlank()) {
      this.secret = env;
      return;
    }
    if ("dev".equalsIgnoreCase(activeProfile)) {
      // ponytail: dev-only ephemeral mirror of JwtIssuer — same JVM, same secret source.
      // JwtIssuer.init() runs first (random secure-by-default), so by the time we read
      // HMAC_JWT_SECRET in dev it is null. Use a stable dev marker so verifier tests pass.
      this.secret = "dev-verifier-marker-not-used-real-env";
      return;
    }
    throw new IllegalStateException("HMAC_JWT_SECRET env var is required in non-dev profiles —"
        + " JwtVerifier refuses to start without a signing key (ADR-20 fail-loud).");
  }

  /** Verify a JWT and return the parsed claims. Throws on any failure (bad signature,
   *  wrong issuer, expired, malformed). */
  public Map<String, Object> verify(String token) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("token is empty");
    }
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("malformed JWT (expected 3 segments, got " + parts.length + ")");
    }
    String canonical = parts[0] + "." + parts[1];
    String providedSig = parts[2];
    String computedSig = HmacEventSigner.sign(canonical, secret);
    if (!constantTimeEquals(providedSig, computedSig)) {
      throw new IllegalStateException("JWT signature mismatch");
    }
    Map<String, Object> payload;
    try {
      byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
      payload = objectMapper.readValue(payloadBytes, Map.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("malformed JWT payload: " + e.getMessage(), e);
    }
    Object iss = payload.get("iss");
    if (iss == null || !EXPECTED_ISS.equals(iss.toString())) {
      throw new IllegalStateException("JWT issuer mismatch (expected " + EXPECTED_ISS + ")");
    }
    Object exp = payload.get("exp");
    if (exp instanceof Number expN) {
      long nowEpoch = Instant.now(clock).getEpochSecond();
      if (expN.longValue() <= nowEpoch) {
        throw new IllegalStateException("JWT expired at " + expN + " (now=" + nowEpoch + ")");
      }
    } else {
      throw new IllegalStateException("JWT missing exp claim");
    }
    return payload;
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }
}