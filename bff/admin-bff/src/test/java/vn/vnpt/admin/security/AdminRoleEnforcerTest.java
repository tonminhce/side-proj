package vn.vnpt.admin.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests for {@link AdminRoleEnforcer} (Story 1.4 / Subtask 5.5). */
class AdminRoleEnforcerTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private final AdminRoleEnforcer enforcer = new AdminRoleEnforcer();

  @Test
  void enforceAllowsStaff() {
    setAuth("ROLE_staff");
    assertThatCode(enforcer::requireStaffOrAdmin).doesNotThrowAnyException();
  }

  @Test
  void enforceAllowsAdmin() {
    setAuth("ROLE_admin");
    assertThatCode(enforcer::requireStaffOrAdmin).doesNotThrowAnyException();
  }

  @Test
  void enforceRejectsCustomer() {
    setAuth("ROLE_customer");
    assertThatThrownBy(enforcer::requireStaffOrAdmin)
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("staff or admin");
  }

  private void setAuth(String... authorities) {
    var auth =
        new UsernamePasswordAuthenticationToken(
            "dev-user",
            "n/a",
            java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}