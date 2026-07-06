package vn.vnpt.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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
import vn.vnpt.catalog.CatalogApplication;
import vn.vnpt.catalog.domain.Product;

/**
 * Full Spring context integration test for {@link CreateProductUseCase} (Story 1.2 / Subtask 9.7).
 *
 * <p>Ponytail skip: {@code create_writesToOutboxInSameTransaction} (rollback invariant) is
 * intentionally NOT implemented — the atomicity is implicit in {@code @Transactional} on the
 * use case, and exercising the rollback path requires TestTransaction tooling that adds
 * complexity disproportionate to the gain.
 */
@SpringBootTest(classes = CatalogApplication.class)
@ActiveProfiles("test")
@Testcontainers
class CreateProductUseCaseTest {

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

  @Autowired CreateProductUseCase useCase;
  @Autowired DataSource dataSource;
  @Autowired ObjectMapper objectMapper;

  @Test
  void create_persistsProductVariantsAndAttributes() {
    Product saved =
        useCase.create(
            new CreateProductCommand(
                "Red Shirt",
                "red-shirt",
                "A red shirt",
                "Acme",
                List.of(
                    new CreateProductCommand.VariantSpec(
                        Map.of("color", "red", "size", "M"), 199000L, "VND"),
                    new CreateProductCommand.VariantSpec(
                        Map.of("color", "red", "size", "L"), 199000L, "VND")),
                List.of(
                    new CreateProductCommand.AttributeSpec("color", "Color", 0),
                    new CreateProductCommand.AttributeSpec("size", "Size", 1))));

    assertThat(saved.getName()).isEqualTo("Red Shirt");
    assertThat(saved.getSku()).isEqualTo("red-shirt");
    assertThat(saved.getUuid()).isNotNull();

    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Integer variantCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM variants WHERE product_uuid = ?",
            Integer.class,
            saved.getUuid());
    assertThat(variantCount).isEqualTo(2);

    Integer attributeCount =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM attributes WHERE product_uuid = ?",
            Integer.class,
            saved.getUuid());
    assertThat(attributeCount).isEqualTo(2);

    Map<String, Object> outboxRow =
        jdbc.queryForMap(
            "SELECT aggregate_type, aggregate_id, event_type, payload::text AS payload"
                + " FROM outbox WHERE aggregate_id = ? LIMIT 1",
            saved.getUuid());
    assertThat(outboxRow.get("aggregate_type")).isEqualTo("Product");
    assertThat(((Number) outboxRow.get("aggregate_id")).longValue()).isEqualTo(saved.getUuid());
    assertThat(outboxRow.get("event_type")).isEqualTo("catalog.product.created");

    JsonNode payload = readJson(outboxRow.get("payload").toString());
    assertThat(payload.get("sku").asText()).isEqualTo("red-shirt");
    assertThat(payload.get("productUuid").asLong()).isEqualTo(saved.getUuid());
  }

  @Test
  void create_validatesName() {
    assertThatThrownBy(
            () ->
                useCase.create(
                    new CreateProductCommand(
                        "", "x", null, null, List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  @Test
  void create_validatesSku() {
    assertThatThrownBy(
            () ->
                useCase.create(
                    new CreateProductCommand(
                        "name", null, null, null, List.of(), List.of())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sku");
  }

  /**
   * AC #11 — when a {@link CreateProductCommand.VariantSpec} omits {@code currency}, the persisted
   * variant defaults to {@code "VND"}. Pin this so a future "currency is required" change surfaces
   * in review.
   */
  @Test
  void create_defaultsCurrencyToVNDWhenNull() {
    Product saved =
        useCase.create(
            new CreateProductCommand(
                "Blue Shirt",
                "blue-shirt-qa",
                null,
                null,
                List.of(
                    new CreateProductCommand.VariantSpec(
                        Map.of("color", "blue"), 100L, /* currency */ null)),
                List.of()));

    List<Map<String, Object>> rows =
        new JdbcTemplate(dataSource)
            .queryForList(
                "SELECT sku, currency FROM variants WHERE product_uuid = ?", saved.getUuid());
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("currency")).isEqualTo("VND");
  }

  /**
   * ADR-04 idempotency — the outbox {@code event_id} is the cross-service dedup key. It MUST be a
   * non-null Snowflake {@code Long} (generated by {@code ModulithOutboxPublisher}); a NULL or 0 here
   * would silently break consumer dedup.
   */
  @Test
  void create_writesOutboxEventIdAsSnowflakeLong() {
    Product saved =
        useCase.create(
            new CreateProductCommand(
                "Green Shirt",
                "green-shirt-qa",
                null,
                null,
                List.of(),
                List.of()));

    Long eventId =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "SELECT event_id FROM outbox WHERE aggregate_id = ?",
                Long.class,
                saved.getUuid());
    assertThat(eventId).isNotNull().isPositive();
  }

  private JsonNode readJson(String s) {
    try {
      return objectMapper.readTree(s);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
