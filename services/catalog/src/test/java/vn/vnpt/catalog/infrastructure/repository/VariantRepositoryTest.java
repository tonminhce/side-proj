package vn.vnpt.catalog.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.vnpt.catalog.CatalogApplication;
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.Variant;

/** Integration test for {@link VariantRepository} (Story 1.2 / Subtask 9.5). */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class VariantRepositoryTest {

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

  @Autowired VariantRepository variants;
  @Autowired ProductRepository products;

  @Test
  void findByProductUuid_returnsVariantsForProduct() {
    Product product = products.save(Product.create("Red Shirt", "vr-sku", null, "Acme"));
    variants.save(
        Variant.builder()
            .productUuid(product.getUuid())
            .sku("vr-sku-aaaaaaaaaaaaaa")
            .attributes(Map.of("color", "red", "size", "M"))
            .priceCents(199000L)
            .currency("VND")
            .build());
    variants.save(
        Variant.builder()
            .productUuid(product.getUuid())
            .sku("vr-sku-bbbbbbbbbbbbbb")
            .attributes(Map.of("color", "red", "size", "L"))
            .priceCents(199000L)
            .currency("VND")
            .build());

    assertThat(variants.findByProductUuid(product.getUuid())).hasSize(2);
  }

  @Test
  void findBySku_returnsVariant() {
    Product product = products.save(Product.create("Red Shirt", "vr-sku-2", null, "Acme"));
    variants.save(
        Variant.builder()
            .productUuid(product.getUuid())
            .sku("vr-sku-2-cccccccccccc")
            .attributes(Map.of("color", "red"))
            .priceCents(100L)
            .currency("VND")
            .build());

    assertThat(variants.findBySku("vr-sku-2-cccccccccccc"))
        .isPresent()
        .get()
        .extracting(Variant::getSku)
        .isEqualTo("vr-sku-2-cccccccccccc");
  }
}
