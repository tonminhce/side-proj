package vn.vnpt.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Dev/test placeholder: parses {@code X-User-Roles: staff,admin} into Spring Security
 * authorities (Story 1.4 / FR-74).
 *
 * <p>{@code @Profile({"dev","test"})} prevents the bean from registering in {@code prod}, so a
 * production deploy cannot be tricked by the header. Story 5.5 deletes this filter and
 * replaces it with util's {@code CustomSecurityExpressionHandler} reading JWT roles.
 *
 * <p>The placeholder is acknowledged in the class JavaDoc with this exact comment per the spec.
 */
@Component
@Profile({"dev", "test"})
public class DevRolesHeaderFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String roles = req.getHeader("X-User-Roles");
    if (roles != null && !roles.isBlank()) {
      List<SimpleGrantedAuthority> authorities =
          Arrays.stream(roles.split(","))
              .map(String::trim)
              .filter(s -> !s.isEmpty())
              .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
              .toList();
      var auth = new UsernamePasswordAuthenticationToken("dev-user", "n/a", authorities);
      SecurityContextHolder.getContext().setAuthentication(auth);
    }
    chain.doFilter(req, res);
  }
}