package vn.vnpt.catalog.infrastructure.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import vn.vnpt.catalog.application.port.AttributeRepository;
import vn.vnpt.catalog.application.port.ProductRepository;
import vn.vnpt.catalog.domain.Attribute;
import vn.vnpt.catalog.domain.Product;

/** Integration test for {@link AttributeRepository} (Story 1.2 / Subtask 9.6). */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class AttributeRepositoryTest {

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

  @Autowired AttributeRepository attributes;
  @Autowired ProductRepository products;

  @Test
  void findByProductUuidOrderBySortOrderAsc_ordersResultsBySortOrder() {
    Product product = products.save(Product.create("Red Shirt", "ar-sku", null, "Acme"));
    attributes.save(
        Attribute.builder()
            .productUuid(product.getUuid())
            .name("material")
            .displayName("Material")
            .sortOrder(2)
            .build());
    attributes.save(
        Attribute.builder()
            .productUuid(product.getUuid())
            .name("color")
            .displayName("Color")
            .sortOrder(0)
            .build());
    attributes.save(
        Attribute.builder()
            .productUuid(product.getUuid())
            .name("size")
            .displayName("Size")
            .sortOrder(1)
            .build());

    assertThat(attributes.findByProductUuidOrderBySortOrderAsc(product.getUuid()))
        .extracting(Attribute::getSortOrder)
        .containsExactly(0, 1, 2);
  }
}
