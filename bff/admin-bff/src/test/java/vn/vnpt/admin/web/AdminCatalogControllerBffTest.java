package vn.vnpt.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import vn.vnpt.admin.security.AdminRoleEnforcer;
import vn.vnpt.catalog.application.query.ProductSummary;
import vn.vnpt.catalog.application.query.VariantSummary;

/**
 * BFF controller test (Story 1.4 / Subtask 5.4). Pure unit test — no Spring context.
 *
 * <p>The BFF's {@code @SpringBootTest} is impractical because the catalog dep drags in
 * Modulith's JDBC event-publication autoconfig, which requires a DataSource (the BFF has
 * none — it's a stateless proxy). Standalone MockMvc + manually-wired controller exercises
 * the same contract: RBAC enforcement + outbound WebClient call + response forwarding.
 */
class AdminCatalogControllerBffTest {

  private MockMvc mvc;
  private WebClient webClient;
  private WebClient.RequestHeadersUriSpec<?> uriSpec;
  private WebClient.RequestHeadersSpec<?> headersSpec;
  private WebClient.ResponseSpec responseSpec;
  private AdminCatalogController controller;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void setup() {
    webClient = org.mockito.Mockito.mock(WebClient.class);
    uriSpec = org.mockito.Mockito.mock(WebClient.RequestHeadersUriSpec.class);
    headersSpec = org.mockito.Mockito.mock(WebClient.RequestHeadersSpec.class);
    responseSpec = org.mockito.Mockito.mock(WebClient.ResponseSpec.class);

    when(webClient.get()).thenReturn((WebClient.RequestHeadersUriSpec) uriSpec);
    when(uriSpec.uri(any(java.util.function.Function.class)))
        .thenReturn((WebClient.RequestHeadersSpec) headersSpec);
    when(headersSpec.header(any(String.class), any(String.class)))
        .thenReturn((WebClient.RequestHeadersSpec) headersSpec);
    when(headersSpec.retrieve()).thenReturn(responseSpec);

    controller = new AdminCatalogController(webClient, new AdminRoleEnforcer());
    mvc = MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalAccessDeniedHandler())
        .build();
  }

  private Page<ProductSummary> stubCatalogResponse() {
    ProductSummary ps =
        new ProductSummary(
            1L,
            "red-shirt",
            "Red Shirt",
            "Acme",
            "A red shirt",
            LocalDateTime.of(2026, 7, 7, 1, 0),
            List.of(new VariantSummary(11L, "red-shirt-r", java.util.Map.of("color", "red"), 199000L, "VND")));
    return new PageImpl<>(List.of(ps), PageRequest.of(0, 20), 1);
  }

  private void setAuth(String... roles) {
    var auth =
        new UsernamePasswordAuthenticationToken(
            "dev-user",
            "n/a",
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @Test
  void list_proxiesToCatalog() throws Exception {
    setAuth("ROLE_staff");
    when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
        .thenReturn(Mono.just(stubCatalogResponse()));

    mvc.perform(get("/bff/admin/catalog/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].sku").value("red-shirt"));

    // Regression: verify the BFF forwards the X-Tenant header. v1 is single-tenant per
    // architecture-detail.md line 78, so X-Tenant must be "default" — NOT the dev principal
    // name "dev-user" (the auth principal name is the SUBJECT, not the tenant).
    org.mockito.ArgumentCaptor<String> tenantCaptor =
        org.mockito.ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(headersSpec)
        .header(org.mockito.ArgumentMatchers.eq("X-Tenant"), tenantCaptor.capture());
    assertThat(tenantCaptor.getValue()).isEqualTo("default");
  }

  @Test
  void list_returns403WhenNoRoles() throws Exception {
    // Empty authentication → "staff or admin role required" (no staff/admin role present).
    setAuth(/* no roles */);
    mvc.perform(get("/bff/admin/catalog/products"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("forbidden"))
        .andExpect(jsonPath("$.message").value("staff or admin role required"));
  }

  @Test
  void list_returns403WhenCustomerRole() throws Exception {
    setAuth("ROLE_customer");
    mvc.perform(get("/bff/admin/catalog/products"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value("staff or admin role required"));
  }
}