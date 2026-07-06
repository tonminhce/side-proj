package vn.vnpt.admin.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Unit tests for {@link DevRolesHeaderFilter} (Story 1.4 / Subtask 5.6). The
 * {@code filterIgnoredInProdProfile} test relies on the bean being excluded via {@code
 * @Profile({"dev","test"}) — verified by inspecting the filter class's annotation, not by
 * spinning a full Spring context (the profile exclusion is checked in CI by reading the
 * compiled bytecode).
 */
class DevRolesHeaderFilterTest {

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void filterSetsAuthenticationWhenHeaderPresent() throws ServletException, IOException {
    DevRolesHeaderFilter filter = new DevRolesHeaderFilter();
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.addHeader("X-User-Roles", "staff,admin");
    MockHttpServletResponse res = new MockHttpServletResponse();
    FilterChain chain = new MockFilterChain();

    filter.doFilter(req, res, chain);

    var auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.getAuthorities())
        .extracting(Object::toString)
        .containsExactlyInAnyOrder("ROLE_staff", "ROLE_admin");
  }

  @Test
  void filterIgnoredInProdProfile() {
    // ponytail: the bean's @Profile({"dev","test"}) keeps it out of the prod context.
    // Verify by reading the class-level annotation, not by full Spring context.
    org.springframework.context.annotation.Profile profile =
        DevRolesHeaderFilter.class.getAnnotation(org.springframework.context.annotation.Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).contains("dev", "test");
    assertThat(profile.value()).doesNotContain("prod");
  }
}