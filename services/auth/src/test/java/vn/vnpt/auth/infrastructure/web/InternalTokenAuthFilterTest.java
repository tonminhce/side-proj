package vn.vnpt.auth.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InternalTokenAuthFilterTest {

  @Test
  void rejectsRequestWithoutHeader() throws Exception {
    InternalTokenAuthFilter filter = new InternalTokenAuthFilter("dev", "");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/service-token");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    assertThat(res.getStatus()).isEqualTo(401);
    assertThat(res.getContentAsString()).contains("unauthorized");
  }

  @Test
  void rejectsRequestWithWrongToken() throws Exception {
    InternalTokenAuthFilter filter = new InternalTokenAuthFilter("dev", "");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/service-token");
    req.addHeader(InternalTokenAuthFilter.HEADER, "wrong-token");
    MockHttpServletResponse res = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, res, chain);
    assertThat(res.getStatus()).isEqualTo(401);
  }

  @Test
  void acceptsRequestWithCorrectDevToken() throws Exception {
    InternalTokenAuthFilter filter = new InternalTokenAuthFilter("dev", "");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/service-token");
    req.addHeader(InternalTokenAuthFilter.HEADER, InternalTokenAuthFilter.DEV_FALLBACK);
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    FilterChain chain = (request, response) -> called.set(true);
    filter.doFilter(req, res, chain);
    assertThat(called.get()).isTrue();
    assertThat(res.getStatus()).isEqualTo(200);
  }

  @Test
  void doesNotFilterOtherPaths() throws Exception {
    InternalTokenAuthFilter filter = new InternalTokenAuthFilter("dev", "");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    FilterChain chain = (request, response) -> called.set(true);
    filter.doFilter(req, res, chain);
    assertThat(called.get()).isTrue();
  }

  @Test
  void configuredTokenTakesPrecedence() throws Exception {
    InternalTokenAuthFilter filter = new InternalTokenAuthFilter("dev", "my-custom-token");
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/service-token");
    req.addHeader(InternalTokenAuthFilter.HEADER, "my-custom-token");
    MockHttpServletResponse res = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();
    FilterChain chain = (request, response) -> called.set(true);
    filter.doFilter(req, res, chain);
    assertThat(called.get()).isTrue();
  }

  @Test
  void nonDevProfile_withoutToken_failsLoud() {
    assertThatThrownBy(() -> new InternalTokenAuthFilter("prod", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_INTERNAL_TOKEN");
  }
}