package vn.vnpt.auth.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/** Service-token gate — Epic 5 follow-up / FR-74 / R-13-adjacent.
 *  The /api/auth/service-token endpoint mints credentials and MUST NOT be publicly
 *  callable. This filter compares the X-Internal-Token header (constant-time) against
 *  the AUTH_INTERNAL_TOKEN env var. In dev profile a known fallback marker is accepted
 *  so smoke scripts work without env wiring; non-dev profiles fail-loud if the env var
 *  is missing (mirrors JwtIssuer ADR-20 contract).
 *  ponytail: header-based token check, not mTLS — upgrade to mTLS or Vault short-lived
 *  tokens when service-mesh sidecars land.
 *  Registered via FilterRegistrationBean (see AuthSecurityConfig) so the order is
 *  explicit HIGHEST_PRECEDENCE — must run BEFORE Spring Security's denyAll chain. */
public class InternalTokenAuthFilter extends OncePerRequestFilter {

  static final String PATH = "/api/auth/service-token";
  static final String HEADER = "X-Internal-Token";
  static final String DEV_FALLBACK = "dev-internal-token-do-not-use-in-prod";

  private final String expected;

  public InternalTokenAuthFilter(@Value("${spring.profiles.active:prod}") String activeProfile,
                                 @Value("${auth.internal-token:}") String configured) {
    String env = System.getenv("AUTH_INTERNAL_TOKEN");
    if (env != null && !env.isBlank()) {
      this.expected = env;
      return;
    }
    if (!configured.isBlank()) {
      this.expected = configured;
      return;
    }
    if ("dev".equalsIgnoreCase(activeProfile)) {
      this.expected = DEV_FALLBACK;
      return;
    }
    throw new IllegalStateException("AUTH_INTERNAL_TOKEN env var is required in non-dev profiles —"
        + " service-token endpoint refuses to start without a gate (ADR-20 fail-loud).");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !PATH.equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String provided = req.getHeader(HEADER);
    if (provided == null || !constantTimeEquals(provided, expected)) {
      res.setStatus(HttpStatus.UNAUTHORIZED.value());
      res.setContentType(MediaType.APPLICATION_JSON_VALUE);
      res.getWriter().write("{\"error\":\"unauthorized\"}");
      return;
    }
    chain.doFilter(req, res);
  }

  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) return false;
    int diff = 0;
    for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
    return diff == 0;
  }
}