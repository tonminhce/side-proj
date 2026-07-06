package vn.vnpt.catalog.infrastructure.web.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.catalog.CatalogApplication;
import vn.vnpt.catalog.application.ListProductsUseCase;
import vn.vnpt.catalog.application.query.ProductSummary;
import vn.vnpt.catalog.application.query.VariantSummary;

/**
 * Controller test for {@link AdminCatalogController} (Story 1.4 / Subtask 5.2).
 *
 * <p>Spring Boot 4.0 removed {@code @WebMvcTest} and {@code @AutoConfigureMockMvc} from
 * spring-boot-test-autoconfigure (only {@code @JsonTest} ships). The supported path is
 * {@code @SpringBootTest(webEnvironment=MOCK)} with a manually-built {@link MockMvc} via
 * {@link MockMvcBuilders#webAppContextSetup}. {@code @MockBean} is replaced by {@code
 * @MockitoBean} (Spring 6.2+ / Spring Boot 4.0).
 */
@SpringBootTest(classes = CatalogApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@Testcontainers
class AdminCatalogControllerTest {

  @Container
  @SuppressWarnings("resource")
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("catalog_db")
          .withUsername("catalog_user")
          .withPassword("catalog_pass");

  @DynamicPropertySource
  static void registerPostgres(DynamicPropertyRegistry registry) {
    registry.add("TC_POSTGRES_URL", POSTGRES::getJdbcUrl);
    registry.add("TC_POSTGRES_USER", POSTGRES::getUsername);
    registry.add("TC_POSTGRES_PASSWORD", POSTGRES::getPassword);
  }

  @Autowired WebApplicationContext wac;
  @MockitoBean ListProductsUseCase listProducts;

  private MockMvc mvc;

  @BeforeEach
  void setup() {
    mvc = MockMvcBuilders.webAppContextSetup(wac).build();
  }

  @Test
  void list_returns200WithShape() throws Exception {
    ProductSummary ps =
        new ProductSummary(
            1L,
            "red-shirt",
            "Red Shirt",
            "Acme",
            "A red shirt",
            LocalDateTime.of(2026, 7, 7, 1, 0),
            List.of(new VariantSummary(11L, "red-shirt-r", java.util.Map.of("color", "red"), 199000L, "VND")));
    Page<ProductSummary> page = new PageImpl<>(List.of(ps), PageRequest.of(0, 20), 1);
    when(listProducts.execute(eq("default"), any())).thenReturn(page);

    mvc.perform(get("/api/admin/catalog/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].sku").value("red-shirt"))
        .andExpect(jsonPath("$.content[0].variants[0].priceCents").value(199000));
  }

  @Test
  void list_returns400OnNegativePage() throws Exception {
    mvc.perform(get("/api/admin/catalog/products").param("page", "-1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"))
        .andExpect(jsonPath("$.message").value("page must be >= 0"));
  }

  // QA-pass gap fill — AC #3 "size < 1" branch at the HTTP boundary.
  @Test
  void list_returns400OnSizeZero() throws Exception {
    mvc.perform(get("/api/admin/catalog/products").param("size", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"));
  }

  // QA-pass gap fill — AC #3 "size > 100" branch at the HTTP boundary.
  @Test
  void list_returns400OnSizeGreaterThan100() throws Exception {
    mvc.perform(get("/api/admin/catalog/products").param("size", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("bad_request"))
        .andExpect(jsonPath("$.message").value("size must be 1..100"));
  }

  @Test
  void list_forwardsTenantFromHeader() throws Exception {
    when(listProducts.execute(any(), any()))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    mvc.perform(get("/api/admin/catalog/products")).andExpect(status().isOk());

    verify(listProducts).execute(eq("default"), any());
  }
}