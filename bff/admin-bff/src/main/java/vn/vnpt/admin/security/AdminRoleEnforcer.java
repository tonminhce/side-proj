package vn.vnpt.admin.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * RBAC enforcer for the admin BFF (Story 1.4 / FR-74). The placeholder dev/test security
 * sets authorities via {@code X-User-Roles}; production (Story 5.5) reads from JWT.
 *
 * <p>Authority strings are {@code ROLE_<name>} — Spring Security's {@code hasRole("staff")}
 * matcher prepends {@code ROLE_} automatically; for direct authority checks the prefix is
 * included. {@link org.springframework.security.core.authority.SimpleGrantedAuthority} used in
 * {@code DevRolesHeaderFilter} prepends {@code ROLE_} too, so the two stay consistent.
 */
@Component
public class AdminRoleEnforcer {

  /** Throws {@link AccessDeniedException} when the caller is not {@code staff} or {@code admin}. */
  public void requireStaffOrAdmin() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null) {
      throw new AccessDeniedException("not authenticated");
    }
    boolean ok =
        auth.getAuthorities().stream()
            .anyMatch(
                a -> "ROLE_staff".equals(a.getAuthority()) || "ROLE_admin".equals(a.getAuthority()));
    if (!ok) {
      throw new AccessDeniedException("staff or admin role required");
    }
  }
}