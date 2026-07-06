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
import vn.vnpt.catalog.domain.Product;

/**
 * Integration test for {@link ProductRepository} (Story 1.2 / Subtask 9.4).
 *
 * <p>Uses {@code @SpringBootTest} — Spring Boot 4.0 removed the {@code @DataJpaTest} test slice
 * (it now lives in {@code spring-boot-test-autoconfigure} which only ships {@code @JsonTest}).
 * The repo slice did not survive the Boot 4 split; full context is the supported path.
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class ProductRepositoryTest {

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

  @Test
  void findBySku_returnsProduct() {
    Product saved = products.save(Product.create("Red Shirt", "pr-sku-1", null, "Acme"));
    assertThat(products.findBySku("pr-sku-1")).isPresent().get().extracting(Product::getSku).isEqualTo("pr-sku-1");
  }

  @Test
  void findBySku_returnsEmptyForUnknownSku() {
    assertThat(products.findBySku("nope")).isEmpty();
  }

  @Test
  void findByIsActiveTrueAndIsDeletedFalse_excludesSoftDeleted() {
    products.save(Product.create("A", "pr-sku-a", null, null));
    products.save(Product.create("B", "pr-sku-b", null, null));
    Product deleted = products.save(Product.create("C", "pr-sku-c", null, null));
    deleted.setIsDeleted(true);
    products.save(deleted);

    assertThat(products.findByIsActiveTrueAndIsDeletedFalse())
        .extracting(Product::getSku)
        .containsExactlyInAnyOrder("pr-sku-a", "pr-sku-b");
  }
}
