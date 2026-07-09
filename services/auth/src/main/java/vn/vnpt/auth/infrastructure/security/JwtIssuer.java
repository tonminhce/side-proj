package vn.vnpt.auth.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import vn.vnpt.util.events.HmacEventSigner;
import vn.vnpt.util.events.JcsCanonicalJson;

/** JWT issuer — Story 5.4 / FR-73. HS256 with HMAC payload via util's HmacEventSigner.
 *  Reads the signing key from {@code HMAC_JWT_SECRET}; in the dev profile only, falls back
 *  to a 32-byte random hex (NEVER logged). Non-dev profiles fail loud on missing key. */
@Component
public class JwtIssuer {

  private static final Logger log = LoggerFactory.getLogger(JwtIssuer.class);
  private static final long TTL_SECONDS = 3600;
  private static final String ISSUER = "auth";

  private final ObjectMapper objectMapper;
  private final String activeProfile;
  private volatile String secret;

  public JwtIssuer(ObjectMapper objectMapper,
                   @Value("${spring.profiles.active:prod}") String activeProfile) {
    this.objectMapper = objectMapper;
    this.activeProfile = activeProfile;
  }

  @PostConstruct
  public void init() {
    String env = System.getenv("HMAC_JWT_SECRET");
    if (env != null && !env.isBlank()) {
      this.secret = env;
      return;
    }
    if ("dev".equalsIgnoreCase(activeProfile)) {
      byte[] random = new byte[32];
      new java.security.SecureRandom().nextBytes(random);
      this.secret = java.util.HexFormat.of().formatHex(random);
      log.warn("JwtIssuer dev-mode: generated ephemeral HMAC secret (regenerated on every restart,"
          + " never use in prod, never log the secret value)");
      return;
    }
    throw new IllegalStateException("HMAC_JWT_SECRET env var is required in non-dev profiles —"
        + " auth refuses to start without a signing key (ADR-20 fail-loud).");
  }

  public String issue(long userId, String email, String role) {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "HS256");
    header.put("typ", "JWT");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("iss", ISSUER);
    payload.put("sub", userId);
    payload.put("email", email);
    payload.put("role", role);
    payload.put("iat", now);
    payload.put("exp", now + TTL_SECONDS);

    return signAndEncode(header, payload);
  }

  /** Story 5.5 / FR-74 — service-account JWT with caller chain + allowed roles. */
  public String issueServiceToken(String serviceAccountId, java.util.List<String> allowedRoles,
                                  java.util.List<String> callerChain) {
    long now = Instant.now().getEpochSecond();
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "HS256");
    header.put("typ", "JWT");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("iss", ISSUER);
    payload.put("sub", "svc:" + serviceAccountId);
    payload.put("role", allowedRoles == null || allowedRoles.isEmpty() ? "USER" : allowedRoles.get(0));
    payload.put("serviceAccountId", serviceAccountId);
    payload.put("callerChain", callerChain == null ? java.util.List.of() : callerChain);
    payload.put("allowedRoles", allowedRoles == null ? java.util.List.of() : allowedRoles);
    payload.put("iat", now);
    payload.put("exp", now + TTL_SECONDS);

    return signAndEncode(header, payload);
  }

  private String signAndEncode(Map<String, Object> header, Map<String, Object> payload) {
    try {
      String h = Base64.getUrlEncoder().withoutPadding().encodeToString(
          objectMapper.writeValueAsBytes(header));
      String p = Base64.getUrlEncoder().withoutPadding().encodeToString(
          JcsCanonicalJson.serialize(payload).getBytes(StandardCharsets.UTF_8));
      String canonical = h + "." + p;
      String sig = HmacEventSigner.sign(canonical, secret);
      return canonical + "." + sig;
    } catch (Exception e) {
      throw new IllegalStateException("JWT issuance failed", e);
    }
  }
}