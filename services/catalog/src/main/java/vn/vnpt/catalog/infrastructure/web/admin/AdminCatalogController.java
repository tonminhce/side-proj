package vn.vnpt.catalog.infrastructure.web.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.catalog.application.ListProductsUseCase;
import vn.vnpt.catalog.application.query.ProductSummary;

/**
 * Admin-facing catalog read endpoint (Story 1.4 / FR-6).
 *
 * <p>The URL prefix is {@code /api/admin/*} even though the module is {@code services/catalog/}
 * — the BFF strips the {@code /bff/admin} prefix and the catalog service owns the {@code
 * /api/admin/*} path. BFF owns auth + URL prefix; services own business logic (architecture.md
 * line 911).
 *
 * <p>NO {@code @PreAuthorize} here — RBAC is the BFF's job (architecture.md line 867-913). The
 * catalog service trusts its caller (internal mTLS + RBAC-pre-checked by the BFF in Story 5.5).
 *
 * <p>FR-7 audit_trail is NOT touched (read-only path).
 */
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController {

  private final ListProductsUseCase listProducts;

  @GetMapping("/products")
  public Page<ProductSummary> list(
      @RequestHeader(name = "X-Tenant", defaultValue = "default") String tenantId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (page < 0) {
      throw new IllegalArgumentException("page must be >= 0");
    }
    if (size < 1 || size > 100) {
      throw new IllegalArgumentException("size must be 1..100");
    }
    return listProducts.execute(tenantId, PageRequest.of(page, size));
  }
}