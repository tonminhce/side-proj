package vn.vnpt.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.vnpt.catalog.CatalogApplication;
import vn.vnpt.catalog.domain.Product;
import vn.vnpt.catalog.domain.exception.ProductNotFoundException;

/**
 * Full Spring context integration tests for {@link UpdateProductUseCase} (Story 1.3 /
 * AC #10 / Subtask 9.7).
 *
 * <p>Asserts: (1) mutable fields updated, (2) sku immutable, (3) outbox row appended with
 * the updated payload, (4) not-found throws the domain exception, (5) blank name rejected.
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class UpdateProductUseCaseTest {

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

  @Autowired UpdateProductUseCase useCase;
  @Autowired CreateProductUseCase createUseCase;
  @Autowired DataSource dataSource;
  @Autowired ObjectMapper objectMapper;

  @Test
  void update_persistsFieldsAndOutboxEvent() {
    Product created =
        createUseCase.create(
            new CreateProductCommand(
                "Original",
                "sku-original-1",
                "Original desc",
                "Original brand",
                List.of(),
                List.of()));

    Product updated =
        useCase.update(
            new UpdateProductCommand(created.getUuid(), "New Name", "New Desc", "New Brand"));

    assertThat(updated.getName()).isEqualTo("New Name");
    assertThat(updated.getDescription()).isEqualTo("New Desc");
    assertThat(updated.getBrand()).isEqualTo("New Brand");
    assertThat(updated.getSku()).isEqualTo("sku-original-1"); // sku unchanged

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer updateCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND event_type ="
                + " 'catalog.product.updated'",
            Integer.class,
            created.getUuid());
    assertThat(updateCount).isEqualTo(1);

    // AC #10: "the event payload's `name` carries the new value — consumers see the
    // post-update state." A regression that captures the pre-update name (e.g. reads
    // `product.getName()` before the setter) would silently ship stale data to consumers.
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM outbox WHERE aggregate_id = ? AND event_type ="
                + " 'catalog.product.updated'",
            String.class,
            created.getUuid());
    JsonNode node = objectMapper.readTree(payload);
    assertThat(node.get("name").asText()).isEqualTo("New Name");
    assertThat(node.get("sku").asText()).isEqualTo("sku-original-1");
    assertThat(node.get("productUuid").asLong()).isEqualTo(created.getUuid());
  }

  @Test
  void update_throwsWhenProductNotFound() {
    assertThatThrownBy(
            () -> useCase.update(new UpdateProductCommand(999_999_999L, "x", "y", "z")))
        .isInstanceOf(ProductNotFoundException.class);
  }

  @Test
  void update_validatesName() {
    Product created =
        createUseCase.create(
            new CreateProductCommand(
                "Original",
                "sku-validation-1",
                null,
                null,
                List.of(),
                List.of()));
    assertThatThrownBy(
            () ->
                useCase.update(
                    new UpdateProductCommand(created.getUuid(), "", "desc", "brand")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  @Test
  void update_doesNotChangeSku() {
    Product created =
        createUseCase.create(
            new CreateProductCommand(
                "Original",
                "sku-immutable-1",
                null,
                null,
                List.of(),
                List.of()));
    useCase.update(
        new UpdateProductCommand(created.getUuid(), "Renamed", null, null));
    // Re-load from DB to confirm the SKU column was not touched.
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String sku =
        jdbc.queryForObject(
            "SELECT sku FROM products WHERE uuid = ?", String.class, created.getUuid());
    assertThat(sku).isEqualTo("sku-immutable-1");
  }
}
