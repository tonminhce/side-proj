package vn.vnpt.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.catalog.CatalogApplication;
import vn.vnpt.catalog.application.port.ProductRepository;
import vn.vnpt.catalog.application.port.VariantRepository;
import vn.vnpt.catalog.application.query.ProductSummary;
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.Variant;

/**
 * Integration test for {@link ListProductsUseCase} (Story 1.4 / Subtask 5.1).
 *
 * <p>{@code @SpringBootTest @ActiveProfiles("test")} with Testcontainers Postgres — same
 * pattern as Story 1.2/1.3 use-case tests.
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ListProductsUseCaseTest {

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

  @Autowired ListProductsUseCase useCase;
  @Autowired ProductRepository products;
  @Autowired VariantRepository variants;

  @Test
  void list_returnsFirstPageWithVariants() {
    Product saved = products.save(Product.create("Red Shirt", "r-s-1", null, "Acme"));
    variants.save(
        Variant.builder()
            .productUuid(saved.getUuid())
            .sku(Variant.computeSku("r-s-1", Map.of("color", "red")))
            .attributes(Map.of("color", "red"))
            .priceCents(199000L)
            .currency("VND")
            .build());

    var page = useCase.execute("default", PageRequest.of(0, 20));

    assertThat(page.getContent()).hasSize(1);
    ProductSummary summary = page.getContent().get(0);
    assertThat(summary.productUuid()).isEqualTo(saved.getUuid());
    assertThat(summary.sku()).isEqualTo("r-s-1");
    assertThat(summary.variants()).hasSize(1);
    assertThat(summary.variants().get(0).priceCents()).isEqualTo(199000L);
    assertThat(summary.variants().get(0).attributes()).containsEntry("color", "red");
  }

  @Test
  void list_filtersByTenant() {
    Product tenantA = Product.builder().name("A").sku("ta").tenantId("tenant-A").build();
    Product tenantB = Product.builder().name("B").sku("tb").tenantId("tenant-B").build();
    products.save(tenantA);
    products.save(tenantB);

    var page = useCase.execute("tenant-A", PageRequest.of(0, 20));

    assertThat(page.getContent()).extracting(ProductSummary::sku).containsExactly("ta");
  }

  @Test
  void list_rejectsSizeGreaterThanMax() {
    assertThatThrownBy(() -> useCase.execute("default", PageRequest.of(0, 200)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }
}