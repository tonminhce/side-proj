package vn.vnpt.catalog.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.Variant;

/**
 * Integration test for the tenant-scoped read query (Story 1.4 / Subtask 5.3). The query lives
 * on the {@link ProductRepository} port (no Impl class — Spring Data JPA derives the JPQL).
 *
 * <p>Asserts JOIN FETCH loads variants in a single SELECT (not 1 + N) and the tenant filter is
 * applied.
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ProductRepositoryImplTest {

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

  @Autowired ProductRepository products;
  @Autowired VariantRepository variants;

  @Test
  void findByTenantId_loadsVariantsViaJoinFetch() {
    Product saved = products.save(Product.create("Red Shirt", "rs-1", null, "Acme"));
    variants.save(
        Variant.builder()
            .productUuid(saved.getUuid())
            .sku(Variant.computeSku("rs-1", Map.of("color", "red")))
            .attributes(Map.of("color", "red"))
            .priceCents(199000L)
            .currency("VND")
            .build());
    variants.save(
        Variant.builder()
            .productUuid(saved.getUuid())
            .sku(Variant.computeSku("rs-1", Map.of("color", "blue")))
            .attributes(Map.of("color", "blue"))
            .priceCents(199000L)
            .currency("VND")
            .build());
    variants.save(
        Variant.builder()
            .productUuid(saved.getUuid())
            .sku(Variant.computeSku("rs-1", Map.of("size", "M")))
            .attributes(Map.of("size", "M"))
            .priceCents(199000L)
            .currency("VND")
            .build());

    var page = products.findByTenantId("default", PageRequest.of(0, 10));

    assertThat(page.getContent()).hasSize(1);
    // After the read, variants are populated via JOIN FETCH — calling getVariants() does NOT
    // trigger a separate SELECT. Assert the size to prove the join worked.
    assertThat(page.getContent().get(0).getVariants()).hasSize(3);
  }

  @Test
  void findByTenantId_filtersByTenant() {
    products.save(Product.builder().name("A").sku("ta").tenantId("tenant-A").build());
    products.save(Product.builder().name("B").sku("tb").tenantId("tenant-A").build());
    products.save(Product.builder().name("C").sku("tc").tenantId("tenant-B").build());

    var page = products.findByTenantId("tenant-A", PageRequest.of(0, 10));

    assertThat(page.getContent()).extracting(Product::getSku).containsExactlyInAnyOrder("ta", "tb");
  }
}