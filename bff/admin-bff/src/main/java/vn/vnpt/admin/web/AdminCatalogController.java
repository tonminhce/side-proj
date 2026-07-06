package vn.vnpt.admin.web;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.Page;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import vn.vnpt.admin.security.AdminRoleEnforcer;
import vn.vnpt.catalog.application.query.ProductSummary;

/**
 * Admin catalog read endpoint (Story 1.4 / FR-6).
 *
 * <p>Proxies {@code /bff/admin/catalog/products} → {@code /api/admin/catalog/products} on the
 * catalog service, gated by {@link AdminRoleEnforcer} (RBAC; the catalog service itself does
 * NOT authenticate — that's the BFF's job, architecture.md line 867-913).
 *
 * <p>{@code Page<T>} Jackson deserialization works because Spring Data's serialization on the
 * catalog side matches; the {@code ParameterizedTypeReference} keeps Jackson from erasing the
 * generic at runtime.
 *
 * <p>Tenant is forwarded from the (placeholder) auth principal name; v1 defaults to
 * {@code 'default'} (architecture-detail.md line 78). Story 5.x reads tenant from JWT claims.
 */
@RestController
@RequestMapping("/bff/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController {

  private final WebClient catalogClient;
  private final AdminRoleEnforcer roleEnforcer;

  @GetMapping("/products")
  public Page<ProductSummary> list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    roleEnforcer.requireStaffOrAdmin();

    // ponytail: forward tenant from the (placeholder) auth principal; Story 5.5 reads JWT claim.
    String tenant = SecurityContextHolder.getContext().getAuthentication().getName();
    if (tenant == null || tenant.isBlank()) {
      tenant = "default";
    }

    // ponytail: BFF blocks; Sprint 1 is not the place for reactive-down-the-stack.
    return catalogClient
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/admin/catalog/products")
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build())
        .header("X-Tenant", tenant)
        .retrieve()
        .bodyToMono(new ParameterizedTypeReference<Page<ProductSummary>>() {})
        .block();
  }
}